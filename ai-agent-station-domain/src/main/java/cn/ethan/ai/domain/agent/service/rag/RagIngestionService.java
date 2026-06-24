package cn.ethan.ai.domain.agent.service.rag;

import cn.ethan.ai.domain.agent.adapter.port.IRagChunkIndexPort;
import cn.ethan.ai.domain.agent.adapter.repository.IRagIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionResultVO;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown RAG 导入服务。章节只用于分块边界，不再维护父子回溯链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionService {

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final int DOC_SUMMARY_MAX_CHARS = 220;
    private static final int ENABLED_STATUS = 1;

    private final TokenTextSplitter tokenTextSplitter;
    private final IRagIngestionRepository ragIngestionRepository;
    private final IRagChunkIndexPort ragChunkIndexPort;

    @Transactional(rollbackFor = Exception.class)
    public RagIngestionResultVO ingestMarkdown(String ragId,
                                                String title,
                                                String source,
                                                String markdownText) {
        if (StringUtils.isBlank(ragId)) {
            throw new IllegalArgumentException("ragId 不能为空");
        }
        if (StringUtils.isBlank(markdownText)) {
            throw new IllegalArgumentException("Markdown 文本不能为空");
        }

        String normalizedSource = StringUtils.defaultIfBlank(source, title);
        String normalizedTitle = resolveDocumentTitle(title, normalizedSource, markdownText);
        String docId = buildDocId(ragId, normalizedSource, normalizedTitle);
        List<MarkdownSection> sections = splitSections(normalizedTitle, markdownText);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("未解析出可用的 Markdown 章节内容");
        }

        List<Document> indexDocuments = buildChunkDocuments(
                ragId, docId, normalizedTitle, normalizedSource, sections);
        List<RagIngestionChunkVO> chunkRecords = indexDocuments.stream()
                .map(document -> RagIngestionChunkVO.builder()
                        .ragId(ragId)
                        .docId(docId)
                        .chunkId(String.valueOf(document.getMetadata().get("chunk_id")))
                        .parentChunkId(null)
                        .chunkLevel(1)
                        .chunkType("markdown_chunk")
                        .chunkText(document.getText())
                        .metadataJson(JSON.toJSONString(document.getMetadata()))
                        .status(ENABLED_STATUS)
                        .build())
                .toList();

        RagIngestionDocumentVO documentRecord = RagIngestionDocumentVO.builder()
                .ragId(ragId)
                .docId(docId)
                .title(normalizedTitle)
                .source(normalizedSource)
                .summary(buildSummary(sections))
                .metadataJson(JSON.toJSONString(Map.of(
                        "doc_type", "markdown",
                        "knowledge_tag", ragId,
                        "source", normalizedSource,
                        "title", normalizedTitle
                )))
                .status(ENABLED_STATUS)
                .build();

        ragIngestionRepository.replaceDocument(documentRecord, chunkRecords);
        ragChunkIndexPort.replaceChunks(documentRecord, indexDocuments);
        log.info("Markdown RAG 导入完成，ragId:{}，docId:{}，chunks:{}", ragId, docId, indexDocuments.size());
        return RagIngestionResultVO.builder()
                .ragId(ragId)
                .docId(docId)
                .title(normalizedTitle)
                .chunkCount(indexDocuments.size())
                .build();
    }

    private List<Document> buildChunkDocuments(String ragId,
                                                String docId,
                                                String title,
                                                String source,
                                                List<MarkdownSection> sections) {
        List<Document> result = new ArrayList<>();
        int chunkOrder = 1;
        for (MarkdownSection section : sections) {
            Map<String, Object> sectionMetadata = baseMetadata(
                    ragId, docId, title, source, section.title(), chunkOrder);
            Document sectionDocument = Document.builder()
                    .id(UUID.nameUUIDFromBytes((docId + ":section:" + chunkOrder)
                            .getBytes(StandardCharsets.UTF_8)).toString())
                    .text(section.title() + System.lineSeparator() + System.lineSeparator() + section.content())
                    .metadata(sectionMetadata)
                    .build();
            for (Document split : tokenTextSplitter.apply(List.of(sectionDocument))) {
                String chunkId = "c_" + String.format("%04d", chunkOrder);
                Map<String, Object> metadata = baseMetadata(
                        ragId, docId, title, source, section.title(), chunkOrder);
                metadata.put("chunk_id", chunkId);
                result.add(Document.builder()
                        .id(UUID.nameUUIDFromBytes((docId + ":" + chunkId)
                                .getBytes(StandardCharsets.UTF_8)).toString())
                        .text(split.getText())
                        .metadata(metadata)
                        .build());
                chunkOrder++;
            }
        }
        return result;
    }

    private Map<String, Object> baseMetadata(String ragId,
                                             String docId,
                                             String title,
                                             String source,
                                             String sectionTitle,
                                             int chunkOrder) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rag_id", ragId);
        metadata.put("doc_id", docId);
        metadata.put("chunk_level", 1);
        metadata.put("chunk_type", "markdown_chunk");
        metadata.put("title", title);
        metadata.put("source", source);
        metadata.put("section_title", sectionTitle);
        metadata.put("chunk_order", chunkOrder);
        metadata.put("knowledge_tag", ragId);
        metadata.put("status", ENABLED_STATUS);
        return metadata;
    }

    private List<MarkdownSection> splitSections(String documentTitle, String markdownText) {
        List<MarkdownSection> sections = new ArrayList<>();
        String currentTitle = StringUtils.defaultIfBlank(documentTitle, "默认章节");
        StringBuilder buffer = new StringBuilder();
        for (String line : markdownText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                flushSection(sections, currentTitle, buffer);
                currentTitle = matcher.group(2).trim();
            } else {
                buffer.append(line).append(System.lineSeparator());
            }
        }
        flushSection(sections, currentTitle, buffer);
        return sections;
    }

    private void flushSection(List<MarkdownSection> sections, String title, StringBuilder buffer) {
        String content = buffer.toString().replaceAll("\\n{3,}", "\n\n").trim();
        if (StringUtils.isNotBlank(content)) {
            sections.add(new MarkdownSection(StringUtils.defaultIfBlank(title, "默认章节"), content));
        }
        buffer.setLength(0);
    }

    private String resolveDocumentTitle(String title, String source, String markdownText) {
        if (StringUtils.isNotBlank(title)) {
            return title.trim();
        }
        for (String line : markdownText.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(line.trim());
            if (matcher.matches() && StringUtils.isNotBlank(matcher.group(2))) {
                return matcher.group(2).trim();
            }
        }
        return StringUtils.defaultIfBlank(source, "未命名 Markdown 文档").trim();
    }

    private String buildDocId(String ragId, String source, String title) {
        return "doc_" + DigestUtils.md5Hex(String.join("::", ragId,
                StringUtils.defaultString(source), StringUtils.defaultString(title))).substring(0, 16);
    }

    private String buildSummary(List<MarkdownSection> sections) {
        String summary = sections.stream()
                .map(section -> section.title() + "：" + section.content())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return summary.length() <= DOC_SUMMARY_MAX_CHARS
                ? summary : summary.substring(0, DOC_SUMMARY_MAX_CHARS);
    }

    private record MarkdownSection(String title, String content) {
    }
}
