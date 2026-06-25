package cn.ethan.ai.domain.agent.service.execute.springai.advisor;

import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将真实 MCP ToolCallback 输出归一化为可追踪 evidence。
 */
@Slf4j
@Service
public class McpEvidenceNormalizer {

    private static final int MAX_EVIDENCE_PER_CALL = 6;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?im)^\\s*title\\s*:\\s*(.+)$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Document> normalize(List<ToolInvocationRecordVO> invocations,
                                    EvidenceSourceTypeEnumVO sourceType,
                                    String query) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        for (ToolInvocationRecordVO invocation : invocations) {
            if (invocation == null || !invocation.isSuccess() || StringUtils.isBlank(invocation.getOutput())) {
                continue;
            }
            int before = documents.size();
            try {
                collectJson(objectMapper.readTree(invocation.getOutput()), invocation, sourceType, query, documents);
            } catch (Exception ignored) {
                // 纯文本 MCP 响应保留为低可信 evidence，不把它伪装成可归因来源。
            }
            if (documents.size() == before) {
                documents.add(buildDocument(invocation, sourceType, query,
                        invocation.getToolName(), "", invocation.getOutput(), false, documents.size() + 1));
            }
            if (documents.size() >= MAX_EVIDENCE_PER_CALL) {
                break;
            }
        }
        return documents.size() <= MAX_EVIDENCE_PER_CALL
                ? documents
                : List.copyOf(documents.subList(0, MAX_EVIDENCE_PER_CALL));
    }

    private void collectJson(JsonNode node,
                             ToolInvocationRecordVO invocation,
                             EvidenceSourceTypeEnumVO sourceType,
                             String query,
                             List<Document> documents) {
        if (node == null || node.isNull() || documents.size() >= MAX_EVIDENCE_PER_CALL) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectJson(child, invocation, sourceType, query, documents));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String content = firstText(node, "content", "text", "snippet", "description", "pageContent");
        String uri = firstText(node, "url", "uri", "link", "sourceUrl");
        String title = firstText(node, "title", "name", "source");
        if (StringUtils.isNotBlank(content)) {
            if (StringUtils.isBlank(uri)) {
                uri = extractUri(content);
            }
            if (StringUtils.isBlank(title)) {
                title = extractTitle(content);
            }
            documents.add(buildDocument(invocation, sourceType, query, title, uri, content,
                    StringUtils.isNotBlank(uri), documents.size() + 1));
        }
        node.properties().forEach(entry -> {
            if (entry.getValue().isContainerNode()) {
                collectJson(entry.getValue(), invocation, sourceType, query, documents);
            }
        });
    }

    private Document buildDocument(ToolInvocationRecordVO invocation,
                                   EvidenceSourceTypeEnumVO sourceType,
                                   String query,
                                   String title,
                                   String uri,
                                   String content,
                                   boolean attributable,
                                   int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("qa_evidence_source_type", sourceType.name());
        metadata.put("qa_retrieval_source", "mcp");
        metadata.put("qa_retrieval_query", query);
        metadata.put("qa_evidence_attributable", attributable);
        metadata.put("tool_name", StringUtils.defaultString(invocation.getToolName()));
        metadata.put("title", StringUtils.defaultIfBlank(title, invocation.getToolName()));
        metadata.put("uri", StringUtils.defaultString(uri));
        metadata.put("retrieved_at", Instant.now().toString());
        return Document.builder()
                .id("mcp:" + StringUtils.defaultIfBlank(invocation.getToolName(), "tool") + ":" + index)
                .text(limit(content, 6000))
                .metadata(metadata)
                .score(attributable ? 0.70D : 0.30D)
                .build();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && StringUtils.isNotBlank(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private String extractUri(String content) {
        Matcher matcher = URL_PATTERN.matcher(StringUtils.defaultString(content));
        if (!matcher.find()) {
            return "";
        }
        return matcher.group().replaceAll("[),.;}\\]]+$", "");
    }

    private String extractTitle(String content) {
        Matcher matcher = TITLE_PATTERN.matcher(StringUtils.defaultString(content));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String limit(String value, int maxLength) {
        String actual = StringUtils.defaultString(value).trim();
        return actual.length() <= maxLength ? actual : actual.substring(0, maxLength) + "...";
    }
}
