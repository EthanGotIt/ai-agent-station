package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.adapter.port.IMcpClientLifecyclePort;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 客户端生命周期管理器。
 * 配置登记不阻塞应用就绪；客户端后台并发预热，并在请求命中路由时按需初始化和复用。
 */
@Slf4j
@Service
public class McpClientLifecycleManager implements IMcpClientLifecyclePort {

    private static final ObjectMapper MCP_OBJECT_MAPPER = new ObjectMapper();

    private static final int DEFAULT_MCP_TIMEOUT_SECONDS = 60;

    private final Map<String, AiClientToolMcpVO> configurations = new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<List<ToolCallback>>> callbackFutures = new ConcurrentHashMap<>();

    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    private final boolean prewarmEnabled;

    private final long resolveTimeoutSeconds;

    private final ExecutorService initializationExecutor;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public McpClientLifecycleManager(
            @Value("${ai-agent.mcp.prewarm-enabled:true}") boolean prewarmEnabled,
            @Value("${ai-agent.mcp.prewarm-concurrency:4}") int prewarmConcurrency,
            @Value("${ai-agent.mcp.resolve-timeout-seconds:5}") long resolveTimeoutSeconds) {
        this.prewarmEnabled = prewarmEnabled;
        this.resolveTimeoutSeconds = Math.max(1, resolveTimeoutSeconds);
        this.initializationExecutor = Executors.newFixedThreadPool(
                Math.max(1, prewarmConcurrency),
                new McpInitializationThreadFactory()
        );
    }

    @Override
    public synchronized void registerConfigurations(List<AiClientToolMcpVO> newConfigurations) {
        Map<String, AiClientToolMcpVO> nextConfigurations = new LinkedHashMap<>();
        if (newConfigurations != null) {
            for (AiClientToolMcpVO configuration : newConfigurations) {
                if (configuration != null && StringUtils.isNotBlank(configuration.getMcpId())) {
                    nextConfigurations.put(configuration.getMcpId(), configuration);
                }
            }
        }

        Set<String> removedMcpIds = new LinkedHashSet<>(configurations.keySet());
        removedMcpIds.removeAll(nextConfigurations.keySet());
        removedMcpIds.forEach(this::evict);

        for (Map.Entry<String, AiClientToolMcpVO> entry : nextConfigurations.entrySet()) {
            AiClientToolMcpVO previous = configurations.put(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                evict(entry.getKey());
                configurations.put(entry.getKey(), entry.getValue());
            }
        }

        log.info("MCP 配置登记完成，配置数：{}，后台预热：{}", configurations.size(), prewarmEnabled);
        if (prewarmEnabled) {
            configurations.values().forEach(this::initializeAsync);
        }
    }

    @Override
    public List<ToolCallback> resolveToolCallbacks(ToolRoutingDecisionVO routingDecision) {
        if (routingDecision == null || !routingDecision.isEnabled()
                || routingDecision.getSelectedMcpIds() == null
                || routingDecision.getSelectedMcpIds().isEmpty()) {
            return Collections.emptyList();
        }

        List<CompletableFuture<List<ToolCallback>>> futures = new ArrayList<>();
        for (String mcpId : new LinkedHashSet<>(routingDecision.getSelectedMcpIds())) {
            AiClientToolMcpVO configuration = configurations.get(mcpId);
            if (configuration == null) {
                log.debug("MCP 路由命中的配置不存在，mcpId：{}", mcpId);
                continue;
            }
            futures.add(initializeAsync(configuration));
        }

        if (futures.isEmpty()) {
            return Collections.emptyList();
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(resolveTimeoutSeconds);
        List<ToolCallback> callbacks = new ArrayList<>();
        for (CompletableFuture<List<ToolCallback>> future : futures) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                log.info("MCP 客户端仍在初始化，本轮按无外部工具继续执行。");
                break;
            }
            try {
                callbacks.addAll(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                log.info("MCP 客户端初始化超过请求期等待上限 {} 秒，本轮按无外部工具继续执行。", resolveTimeoutSeconds);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待 MCP 客户端初始化时线程被中断，本轮按无外部工具继续执行。");
                break;
            } catch (ExecutionException e) {
                log.warn("MCP 客户端初始化失败，本轮跳过该工具，原因：{}", rootMessage(e));
            }
        }
        return callbacks;
    }

    protected List<ToolCallback> initializeToolCallbacks(AiClientToolMcpVO configuration) {
        McpSyncClient client = null;
        try {
            client = createMcpSyncClient(configuration);
            client.initialize();
            ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(List.of(client))
                    .build()
                    .getToolCallbacks();
            List<ToolCallback> resolvedCallbacks = callbacks == null ? List.of() : List.of(callbacks);
            if (closed.get() || !configuration.equals(configurations.get(configuration.getMcpId()))) {
                closeQuietly(client);
                return List.of();
            }
            closeQuietly(clients.put(configuration.getMcpId(), client));
            log.info("MCP 客户端初始化完成，mcpId：{}，mcpName：{}，传输协议：{}，工具数：{}",
                    configuration.getMcpId(), configuration.getMcpName(), configuration.getTransportType(), resolvedCallbacks.size());
            return resolvedCallbacks;
        } catch (Exception e) {
            closeQuietly(client);
            throw new IllegalStateException("MCP 初始化失败，mcpId=" + configuration.getMcpId() + "，原因=" + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        callbackFutures.values().forEach(future -> future.cancel(true));
        clients.values().forEach(this::closeQuietly);
        initializationExecutor.shutdownNow();
    }

    private CompletableFuture<List<ToolCallback>> initializeAsync(AiClientToolMcpVO configuration) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String mcpId = configuration.getMcpId();
        CompletableFuture<List<ToolCallback>> future = callbackFutures.computeIfAbsent(
                mcpId,
                ignored -> CompletableFuture.supplyAsync(() -> initializeToolCallbacks(configuration), initializationExecutor)
        );
        future.whenComplete((callbacks, error) -> {
            if (error != null) {
                callbackFutures.remove(mcpId, future);
                if (!closed.get() && !(rootCause(error) instanceof CancellationException)) {
                    log.warn("MCP 后台初始化失败，可在后续路由命中时重试，mcpId：{}，原因：{}", mcpId, rootMessage(error));
                }
            }
        });
        return future;
    }

    private McpSyncClient createMcpSyncClient(AiClientToolMcpVO configuration) {
        Duration timeout = resolveTimeout(configuration);
        return switch (configuration.getTransportType()) {
            case "stdio" -> createStdioClient(configuration, timeout);
            case "streamable_http" -> createStreamableHttpClient(configuration, timeout);
            default -> throw new IllegalArgumentException("不支持的 MCP 传输协议：" + configuration.getTransportType());
        };
    }

    private McpSyncClient createStdioClient(AiClientToolMcpVO configuration, Duration timeout) {
        AiClientToolMcpVO.TransportConfigStdio transportConfig = configuration.getTransportConfigStdio();
        if (transportConfig == null || StringUtils.isBlank(transportConfig.getCommand())) {
            throw new IllegalArgumentException("Stdio MCP 配置缺少 command，mcpId：" + configuration.getMcpId());
        }

        StdioCommand stdioCommand = normalizeStdioCommand(transportConfig.getCommand(), transportConfig.getArgs());
        Map<String, String> environment = new LinkedHashMap<>();
        if (transportConfig.getEnv() != null) {
            environment.putAll(transportConfig.getEnv());
        }
        environment.putIfAbsent("npm_config_prefer_offline", "true");

        ServerParameters stdioParams = ServerParameters.builder(stdioCommand.command())
                .args(stdioCommand.args())
                .env(environment)
                .build();
        return McpClient.sync(new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(MCP_OBJECT_MAPPER)))
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    private McpSyncClient createStreamableHttpClient(AiClientToolMcpVO configuration, Duration timeout) {
        AiClientToolMcpVO.TransportConfigStreamableHttp transportConfig = configuration.getTransportConfigStreamableHttp();
        if (transportConfig == null || StringUtils.isBlank(transportConfig.getBaseUri())) {
            throw new IllegalArgumentException("Streamable HTTP MCP 配置缺少 baseUri，mcpId：" + configuration.getMcpId());
        }

        ParsedHttpAddress parsedAddress = parseHttpAddress(transportConfig.getBaseUri(), transportConfig.getEndpoint());
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(parsedAddress.baseUri())
                .endpoint(parsedAddress.endpoint());
        if (transportConfig.getHeaders() != null && !transportConfig.getHeaders().isEmpty()) {
            builder.customizeRequest(requestBuilder -> applyHeaders(requestBuilder, transportConfig.getHeaders()));
        }
        return McpClient.sync(builder.build())
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
    }

    private Duration resolveTimeout(AiClientToolMcpVO configuration) {
        Integer timeoutSeconds = configuration.getRequestTimeout();
        return Duration.ofSeconds(timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_MCP_TIMEOUT_SECONDS : timeoutSeconds);
    }

    private StdioCommand normalizeStdioCommand(String command, List<String> configuredArgs) {
        List<String> args = configuredArgs == null ? List.of() : configuredArgs;
        if (!isWindows() || (!command.endsWith(".cmd") && !command.endsWith(".bat"))) {
            return new StdioCommand(command, args);
        }

        List<String> wrappedArgs = new ArrayList<>();
        wrappedArgs.add("/c");
        wrappedArgs.add(command);
        wrappedArgs.addAll(args);
        return new StdioCommand("cmd.exe", wrappedArgs);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void applyHeaders(HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        headers.forEach((key, value) -> {
            if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
                requestBuilder.setHeader(key.trim(), value.trim());
            }
        });
    }

    private ParsedHttpAddress parseHttpAddress(String originalBaseUri, String configuredEndpoint) {
        String baseUri = originalBaseUri.trim();
        String endpoint = configuredEndpoint;
        try {
            URI uri = URI.create(baseUri);
            if (StringUtils.isNotBlank(uri.getScheme()) && StringUtils.isNotBlank(uri.getAuthority())) {
                baseUri = uri.getScheme() + "://" + uri.getAuthority();
            }
            if (StringUtils.isBlank(endpoint)) {
                String path = uri.getRawPath();
                endpoint = StringUtils.isBlank(path) || "/".equals(path) ? "/mcp" : path;
                if (StringUtils.isNotBlank(uri.getRawQuery())) {
                    endpoint = endpoint + "?" + uri.getRawQuery();
                }
            }
        } catch (Exception ignore) {
            if (StringUtils.isBlank(endpoint)) {
                endpoint = "/mcp";
            }
        }
        endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return new ParsedHttpAddress(baseUri, endpoint);
    }

    private synchronized void evict(String mcpId) {
        configurations.remove(mcpId);
        CompletableFuture<List<ToolCallback>> future = callbackFutures.remove(mcpId);
        if (future != null) {
            future.cancel(true);
        }
        closeQuietly(clients.remove(mcpId));
    }

    private void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                client.close();
            } else if (!client.closeGracefully()) {
                client.close();
            }
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端失败", e);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = rootCause(throwable);
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record ParsedHttpAddress(String baseUri, String endpoint) {
    }

    private record StdioCommand(String command, List<String> args) {
    }

    private static class McpInitializationThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "mcp-initialize-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

}
