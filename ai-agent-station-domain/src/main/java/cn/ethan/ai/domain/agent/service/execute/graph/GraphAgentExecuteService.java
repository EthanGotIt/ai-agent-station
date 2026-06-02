package cn.ethan.ai.domain.agent.service.execute.graph;

import cn.ethan.ai.domain.agent.adapter.port.IAgentRuntimeAssemblyPort;
import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.adapter.port.IMcpClientLifecyclePort;
import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentRuntimeConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.McpClientLifecycleSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI Alibaba ReactAgent 的单 Agent Graph Runtime。
 */
@Slf4j
@Service
public class GraphAgentExecuteService {

    private static final String GRAPH_STEP_ID = "graph_runtime";

    private static final int DEFAULT_MAX_CALLS = 8;

    private static final int DEFAULT_CHARS_PER_TOKEN = 4;

    private static final int MIN_CHARS_PER_TOKEN = 1;

    private static final int MAX_CHARS_PER_TOKEN = 8;

    @Resource
    private IAgentRepository repository;

    @Resource
    private IAgentRunRepository agentRunRepository;

    @Resource
    private IAgentRuntimeAssemblyPort runtimeAssemblyPort;

    @Resource
    private IMcpClientLifecyclePort mcpClientLifecyclePort;

    @Resource
    private RuntimeToolCapabilityService toolCapabilityService;

    @Resource
    private ObjectProvider<IRagRetrievalPort> ragRetrievalPortProvider;

    @Resource
    private ObjectProvider<BaseCheckpointSaver> checkpointSaverProvider;

    @Resource
    private AgentGraphRunRegistry runRegistry;

    @Value("${ai-agent.graph.summarization.max-tokens-before-summary:6000}")
    private Integer maxTokensBeforeSummary;

    @Value("${ai-agent.graph.summarization.messages-to-keep:6}")
    private Integer messagesToKeep;

    @Value("${ai-agent.graph.summarization.chars-per-token:4}")
    private Integer charsPerToken;

    public void execute(ExecuteCommandEntity command, IAgentStreamPort streamPort) {
        String runId = UUID.randomUUID().toString();
        String sessionId = StringUtils.defaultIfBlank(command.getSessionId(), runId);
        AiAgentRuntimeConfigVO runtimeConfig = requireRuntimeConfig(command.getAiAgentId());
        LocalDateTime startedAt = LocalDateTime.now();
        AgentRunRecordVO runRecord = buildRunRecord(command, runId, sessionId, startedAt);
        AgentStepRunRecordVO stepRecord = buildStepRecord(runId, startedAt);
        agentRunRepository.createRun(runRecord);
        agentRunRepository.createStep(stepRecord);

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(toThreadId(sessionId))
                .build();
        ReactAgent reactAgent = null;
        try {
            ToolRoutingDecisionVO routingDecision = toolCapabilityService.routeTools(
                    List.of(runtimeConfig.getClientId()),
                    command.getMessage()
            );
            long toolResolutionStartedAt = System.nanoTime();
            List<ToolCallback> tools = new ArrayList<>(runtimeAssemblyPort.resolveMcpToolCallbacks(routingDecision));
            long toolResolutionMillis = elapsedMillis(toolResolutionStartedAt);
            GraphRagSearchToolCallback ragSearchTool = createRagSearchTool();
            if (ragSearchTool != null) {
                tools.add(ragSearchTool);
            }
            McpClientLifecycleSnapshotVO routedMcpSnapshot = mcpClientLifecyclePort.snapshot(routingDecision.getSelectedMcpIds());

            ChatModel chatModel = runtimeAssemblyPort.resolveChatModel(runtimeConfig.getClientId());
            List<Interceptor> interceptors = buildInterceptors(streamPort, sessionId, runId, tools);
            reactAgent = ReactAgent.builder()
                    .name("ai-agent-station")
                    .description("可追踪、可中断、带工具治理的通用智能体")
                    .instruction(buildInstruction())
                    .chatClient(runtimeAssemblyPort.resolveChatClient(runtimeConfig.getClientId()))
                    .tools(tools)
                    .saver(requireCheckpointSaver())
                    .releaseThread(false)
                    .hooks(
                            buildSummarizationHook(chatModel),
                            ModelCallLimitHook.builder()
                                    .runLimit(resolveLimit(command.getMaxStep(), runtimeConfig.getMaxModelCalls()))
                                    .build(),
                            ToolCallLimitHook.builder()
                                    .runLimit(resolveLimit(command.getMaxStep(), runtimeConfig.getMaxToolCalls()))
                                    .build()
                    )
                    .interceptors(interceptors)
                    .enableLogging(true)
                    .build();
            runRegistry.register(runId, reactAgent, runnableConfig);

            sendRuntimeStart(streamPort, sessionId, runId, runnableConfig, routingDecision, tools,
                    routedMcpSnapshot, toolResolutionMillis);
            AssistantMessage response = reactAgent.call(command.getMessage(), runnableConfig);
            if (isCancelled(runId)) {
                markCancelled(runRecord, stepRecord, "执行期间收到取消请求");
                return;
            }

            String content = response == null ? "" : StringUtils.defaultString(response.getText());
            if (ragSearchTool != null && !ragSearchTool.lastEvidencePayload().isEmpty()) {
                streamPort.send(AgentExecuteResultEntity.createExecutionSubResult(
                        null, "rag_evidence", "Agentic RAG 已完成检索并生成 evidence。",
                        ragSearchTool.lastEvidencePayload(), sessionId, runId
                ));
            }
            markSuccess(runRecord, stepRecord, content);
            streamPort.send(AgentExecuteResultEntity.createSummaryResult(content, sessionId, runId));
            streamPort.send(AgentExecuteResultEntity.createCompleteResult(sessionId, runId));
        } catch (Exception e) {
            if (isCancelled(runId)) {
                markCancelled(runRecord, stepRecord, "执行期间收到取消请求");
                return;
            }
            markFailed(runRecord, stepRecord, e.getMessage());
            throw new AgentExecutionException(runId, e.getMessage(), e);
        } finally {
            runRegistry.unregister(runId);
        }
    }

    private void sendRuntimeStart(IAgentStreamPort streamPort,
                                  String sessionId,
                                  String runId,
                                  RunnableConfig runnableConfig,
                                  ToolRoutingDecisionVO routingDecision,
                                  List<ToolCallback> tools,
                                  McpClientLifecycleSnapshotVO routedMcpSnapshot,
                                  long toolResolutionMillis) {
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("sessionId", sessionId);
        boundary.put("threadId", runnableConfig.threadId().orElse(""));
        boundary.put("checkpointSaver", "PostgresSaver");
        boundary.put("longTermStoreEnabled", false);
        boundary.put("summarizationHookEnabled", true);
        streamPort.send(AgentExecuteResultEntity.createAnalysisSubResult(
                null, "context_boundary", "Graph Runtime 已绑定 session checkpoint。", boundary, sessionId, runId
        ));
        streamPort.send(AgentExecuteResultEntity.createAnalysisSubResult(
                null, "tool_routing", routingDecision.getSummary(), routingDecision, sessionId, runId
        ));
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("toolResolutionMillis", toolResolutionMillis);
        lifecycle.put("injectedToolCount", tools.size());
        lifecycle.put("mcpClients", routedMcpSnapshot);
        streamPort.send(AgentExecuteResultEntity.createExecutionSubResult(
                null, "graph_lifecycle", "ReactAgent GraphRuntime 开始执行，可用工具数：" + tools.size(),
                lifecycle, sessionId, runId
        ));
    }

    private SummarizationHook buildSummarizationHook(ChatModel chatModel) {
        return SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(maxTokensBeforeSummary)
                .messagesToKeep(messagesToKeep)
                .tokenCounter(TokenCounter.approximateMsgCounter(normalizeCharsPerToken(charsPerToken)))
                .build();
    }

    private List<Interceptor> buildInterceptors(IAgentStreamPort streamPort,
                                                String sessionId,
                                                String runId,
                                                List<ToolCallback> tools) {
        List<Interceptor> interceptors = new ArrayList<>();
        interceptors.add(TodoListInterceptor.builder()
                .todoEventHandler(todos -> streamPort.send(AgentExecuteResultEntity.createAnalysisSubResult(
                        null, "todo_update", "Graph Runtime 已更新任务清单。", todos, sessionId, runId
                )))
                .build());
        interceptors.add(new StructuredToolErrorInterceptor());

        Set<String> retryableToolNames = tools.stream()
                .filter(tool -> tool != null && tool.getToolDefinition() != null)
                .map(tool -> tool.getToolDefinition().name())
                .filter(StringUtils::isNotBlank)
                .map(ToolGuardPolicy::normalize)
                .filter(toolName -> !"rag_search".equals(toolName))
                .filter(ToolGuardPolicy::isRetryable)
                .collect(Collectors.toSet());
        if (!retryableToolNames.isEmpty()) {
            interceptors.add(ToolRetryInterceptor.builder()
                    .toolNames(retryableToolNames)
                    .maxRetries(1)
                    .initialDelay(200)
                    .maxDelay(500)
                    .jitter(false)
                    .retryOn(this::isRetryableException)
                    .onFailure(ToolRetryInterceptor.OnFailureBehavior.RAISE)
                    .build());
        }
        return interceptors;
    }

    static int normalizeCharsPerToken(Integer configuredValue) {
        if (configuredValue == null) {
            return DEFAULT_CHARS_PER_TOKEN;
        }
        return Math.max(MIN_CHARS_PER_TOKEN, Math.min(MAX_CHARS_PER_TOKEN, configuredValue));
    }

    private boolean isRetryableException(Exception exception) {
        Throwable root = rootCause(exception);
        return !(root instanceof IllegalArgumentException) && !(root instanceof ToolGuardException);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private GraphRagSearchToolCallback createRagSearchTool() {
        IRagRetrievalPort retrievalPort = ragRetrievalPortProvider.getIfAvailable();
        return retrievalPort == null ? null : new GraphRagSearchToolCallback(retrievalPort);
    }

    private BaseCheckpointSaver requireCheckpointSaver() {
        BaseCheckpointSaver saver = checkpointSaverProvider.getIfAvailable();
        if (saver == null) {
            throw new IllegalStateException("Graph checkpoint 未启用，请检查 ai-agent.graph.checkpoint.enabled 和 PostgreSQL 配置");
        }
        return saver;
    }

    private AiAgentRuntimeConfigVO requireRuntimeConfig(String agentId) {
        AiAgentRuntimeConfigVO runtimeConfig = repository.queryAiAgentRuntimeConfig(agentId);
        if (runtimeConfig == null || StringUtils.isBlank(runtimeConfig.getClientId())) {
            throw new IllegalStateException("未找到可用 Graph Runtime 配置，agentId=" + agentId);
        }
        return runtimeConfig;
    }

    private int resolveLimit(Integer compatibilityMaxStep, Integer configuredLimit) {
        int configured = configuredLimit == null || configuredLimit <= 0 ? DEFAULT_MAX_CALLS : configuredLimit;
        if (compatibilityMaxStep == null || compatibilityMaxStep <= 0) {
            return configured;
        }
        return Math.max(1, Math.min(12, compatibilityMaxStep));
    }

    private String toThreadId(String sessionId) {
        return UUID.nameUUIDFromBytes(sessionId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isCancelled(String runId) {
        return runRegistry.isCancelled(runId) || agentRunRepository.isCancelled(runId);
    }

    private AgentRunRecordVO buildRunRecord(ExecuteCommandEntity command,
                                            String runId,
                                            String sessionId,
                                            LocalDateTime startedAt) {
        return AgentRunRecordVO.builder()
                .runId(runId)
                .agentId(command.getAiAgentId())
                .sessionId(sessionId)
                .userMessage(command.getMessage())
                .status(AgentRunStatusEnumVO.RUNNING)
                .startTime(startedAt)
                .build();
    }

    private AgentStepRunRecordVO buildStepRecord(String runId, LocalDateTime startedAt) {
        return AgentStepRunRecordVO.builder()
                .runId(runId)
                .stepId(GRAPH_STEP_ID)
                .stepName("ReactAgent Graph Runtime")
                .stepOrder(1)
                .stepType("GRAPH")
                .status(AgentStepRunStatusEnumVO.RUNNING)
                .startTime(startedAt)
                .build();
    }

    private void markSuccess(AgentRunRecordVO runRecord, AgentStepRunRecordVO stepRecord, String content) {
        LocalDateTime endedAt = LocalDateTime.now();
        stepRecord.setStatus(AgentStepRunStatusEnumVO.SUCCESS);
        stepRecord.setOutputSummary(clip(content, 1000));
        stepRecord.setEndTime(endedAt);
        stepRecord.setCostMillis(durationMillis(stepRecord.getStartTime(), endedAt));
        runRecord.setStatus(AgentRunStatusEnumVO.SUCCESS);
        runRecord.setFinalSummary(content);
        runRecord.setEndTime(endedAt);
        agentRunRepository.updateStep(stepRecord);
        agentRunRepository.updateRun(runRecord);
    }

    private void markFailed(AgentRunRecordVO runRecord, AgentStepRunRecordVO stepRecord, String error) {
        LocalDateTime endedAt = LocalDateTime.now();
        stepRecord.setStatus(AgentStepRunStatusEnumVO.FAILED);
        stepRecord.setErrorMessage(clip(error, 1000));
        stepRecord.setEndTime(endedAt);
        stepRecord.setCostMillis(durationMillis(stepRecord.getStartTime(), endedAt));
        runRecord.setStatus(AgentRunStatusEnumVO.FAILED);
        runRecord.setErrorMessage(clip(error, 1000));
        runRecord.setEndTime(endedAt);
        agentRunRepository.updateStep(stepRecord);
        agentRunRepository.updateRun(runRecord);
    }

    private void markCancelled(AgentRunRecordVO runRecord, AgentStepRunRecordVO stepRecord, String reason) {
        LocalDateTime endedAt = LocalDateTime.now();
        stepRecord.setStatus(AgentStepRunStatusEnumVO.CANCELLED);
        stepRecord.setErrorMessage(reason);
        stepRecord.setEndTime(endedAt);
        stepRecord.setCostMillis(durationMillis(stepRecord.getStartTime(), endedAt));
        runRecord.setStatus(AgentRunStatusEnumVO.CANCELLED);
        runRecord.setCancelReason(reason);
        runRecord.setEndTime(endedAt);
        agentRunRepository.updateStep(stepRecord);
        agentRunRepository.updateRun(runRecord);
    }

    private long durationMillis(LocalDateTime start, LocalDateTime end) {
        return java.time.Duration.between(start, end).toMillis();
    }

    private String clip(String text, int limit) {
        String value = StringUtils.defaultString(text);
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private String buildInstruction() {
        return """
                你是 AI Agent Station 的执行智能体。请直接解决用户目标。
                对复杂任务，先使用 write_todos 维护任务清单，再逐项完成。
                可用 MCP 工具已经过运行时路由和 Tool Guard 筛选；需要外部信息时按需调用，不需要时直接回答。
                需要依据项目知识库时调用 rag_search，并基于返回证据回答；证据不足时明确说明。
                工具不可用、参数错误或调用失败时不得编造结果，应说明失败并给出替代方案。
                """;
    }

}
