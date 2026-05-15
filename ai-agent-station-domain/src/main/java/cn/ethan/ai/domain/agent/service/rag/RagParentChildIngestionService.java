package cn.ethan.ai.domain.agent.service.rag;

import cn.ethan.ai.domain.agent.adapter.port.IRagChildChunkIndexPort;
import cn.ethan.ai.domain.agent.adapter.repository.IRagParentChildIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import cn.ethan.ai.domain.agent.model.valobj.RagParentChildIngestionResultVO;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown Parent-Child 导入服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagParentChildIngestionService {

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final int MAX_PARENT_CHARS = 1800;
    private static final int DOC_SUMMARY_MAX_CHARS = 220;
    private static final int ENABLED_STATUS = 1;

    private final TokenTextSplitter tokenTextSplitter;
    private final IRagParentChildIngestionRepository ragParentChildIngestionRepository;
    private final IRagChildChunkIndexPort ragChildChunkIndexPort;

    @Transactional(rollbackFor = Exception.class)
    public RagParentChildIngestionResultVO ingestMarkdown(String ragId,
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

        List<MarkdownSection> parentSections = splitParentSections(normalizedTitle, markdownText);
        if (parentSections.isEmpty()) {
            throw new IllegalArgumentException("未解析出可用的 Markdown 章节内容");
        }

        List<RagIngestionChunkVO> chunkRecords = new ArrayList<>();
        List<Document> childIndexDocuments = new ArrayList<>();
        int parentOrder = 1;
        int childOrder = 1;
        for (MarkdownSection section : parentSections) {
            String parentChunkId = buildParentChunkId(parentOrder);
            Map<String, Object> parentMetadata = buildChunkMetadata(ragId, docId, normalizedTitle, normalizedSource,
                    section.sectionTitle(), parentOrder, parentChunkId, null, 1, "markdown_parent");
            String parentText = buildParentText(section.sectionTitle(), section.content());
            chunkRecords.add(RagIngestionChunkVO.builder()
                    .ragId(ragId)
                    .docId(docId)
                    .chunkId(parentChunkId)
                    .parentChunkId(null)
                    .chunkLevel(1)
                    .chunkType("markdown_parent")
                    .chunkText(parentText)
                    .metadataJson(JSON.toJSONString(parentMetadata))
                    .status(ENABLED_STATUS)
                    .build());

            List<Document> childDocuments = buildChildDocuments(ragId, docId, normalizedTitle, normalizedSource,
                    section.sectionTitle(), parentChunkId, parentText, childOrder);
            for (Document childDocument : childDocuments) {
                String chunkId = Objects.toString(childDocument.getMetadata().get("chunk_id"), "");
                String metadataJson = JSON.toJSONString(childDocument.getMetadata());
                chunkRecords.add(RagIngestionChunkVO.builder()
                        .ragId(ragId)
                        .docId(docId)
                        .chunkId(chunkId)
                        .parentChunkId(parentChunkId)
                        .chunkLevel(2)
                        .chunkType("markdown_child")
                        .chunkText(childDocument.getText())
                        .metadataJson(metadataJson)
                        .status(ENABLED_STATUS)
                        .build());
            }
            childIndexDocuments.addAll(childDocuments);
            childOrder += childDocuments.size();
            parentOrder++;
        }

        RagIngestionDocumentVO documentRecord = RagIngestionDocumentVO.builder()
                .ragId(ragId)
                .docId(docId)
                .title(normalizedTitle)
                .source(normalizedSource)
                .summary(buildSummary(parentSections))
                .metadataJson(JSON.toJSONString(Map.of(
                        "doc_type", "markdown",
                        "knowledge_tag", ragId,
                        "source", normalizedSource,
                        "title", normalizedTitle
                )))
                .status(ENABLED_STATUS)
                .build();

        ragParentChildIngestionRepository.replaceDocument(documentRecord, chunkRecords);
        ragChildChunkIndexPort.replaceChildChunks(documentRecord, childIndexDocuments);

        log.info("Markdown Parent-Child 导入完成，ragId:{}，docId:{}，parentChunks:{}，childChunks:{}",
                ragId, docId, parentSections.size(), childIndexDocuments.size());
        return RagParentChildIngestionResultVO.builder()
                .ragId(ragId)
                .docId(docId)
                .title(normalizedTitle)
                .parentChunkCount(parentSections.size())
                .childChunkCount(childIndexDocuments.size())
                .build();
    }

    private String resolveDocumentTitle(String title, String source, String markdownText) {
        if (StringUtils.isNotBlank(title)) {
            return title.trim();
        }
        Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(firstNonBlankLine(markdownText));
        if (matcher.matches() && StringUtils.isNotBlank(matcher.group(2))) {
            return matcher.group(2).trim();
        }
        if (StringUtils.isNotBlank(source)) {
            return source.trim();
        }
        return "未命名 Markdown 文档";
    }

    private String buildDocId(String ragId, String source, String title) {
        String raw = String.join("::", StringUtils.defaultString(ragId), StringUtils.defaultString(source), StringUtils.defaultString(title));
        return "doc_" + DigestUtils.md5Hex(raw).substring(0, 16);
    }

    private List<MarkdownSection> splitParentSections(String documentTitle, String markdownText) {
        List<MarkdownSection> sections = new ArrayList<>();
        String currentSectionTitle = StringUtils.defaultIfBlank(documentTitle, "默认章节");
        StringBuilder buffer = new StringBuilder();

        String[] lines = markdownText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                flushSection(sections, currentSectionTitle, buffer);
                currentSectionTitle = matcher.group(2).trim();
                continue;
            }
            buffer.append(line).append(System.lineSeparator());
        }
        flushSection(sections, currentSectionTitle, buffer);

        List<MarkdownSection> normalizedSections = new ArrayList<>();
        for (MarkdownSection section : sections) {
            normalizedSections.addAll(splitOversizedSection(section));
        }
        return normalizedSections;
    }

    private void flushSection(List<MarkdownSection> sections, String sectionTitle, StringBuilder buffer) {
        String content = normalizeSectionBody(buffer.toString());
        if (StringUtils.isBlank(content)) {
            buffer.setLength(0);
            return;
        }
        sections.add(new MarkdownSection(StringUtils.defaultIfBlank(sectionTitle, "默认章节"), content));
        buffer.setLength(0);
    }

    private List<MarkdownSection> splitOversizedSection(MarkdownSection section) {
        if (section.content().length() <= MAX_PARENT_CHARS) {
            return List.of(section);
        }

        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : section.content().split("\\n\\s*\\n")) {
            String normalizedParagraph = normalizeSectionBody(paragraph);
            if (StringUtils.isNotBlank(normalizedParagraph)) {
                paragraphs.add(normalizedParagraph);
            }
        }
        if (paragraphs.isEmpty()) {
            return hardSplitSection(section);
        }

        List<MarkdownSection> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int sequence = 1;
        for (String paragraph : paragraphs) {
            String candidate = current.length() == 0 ? paragraph : current + System.lineSeparator() + System.lineSeparator() + paragraph;
            if (current.length() > 0 && candidate.length() > MAX_PARENT_CHARS) {
                sections.add(new MarkdownSection(section.sectionTitle() + "（片段" + sequence + "）", current.toString().trim()));
                current.setLength(0);
                sequence++;
            }

            if (paragraph.length() > MAX_PARENT_CHARS) {
                sections.addAll(hardSplitSection(new MarkdownSection(section.sectionTitle() + "（片段" + sequence + "）", paragraph)));
                sequence++;
                continue;
            }
            if (current.length() > 0) {
                current.append(System.lineSeparator()).append(System.lineSeparator());
            }
            current.append(paragraph);
        }

        if (current.length() > 0) {
            sections.add(new MarkdownSection(section.sectionTitle() + "（片段" + sequence + "）", current.toString().trim()));
        }
        return sections;
    }

    private List<MarkdownSection> hardSplitSection(MarkdownSection section) {
        List<MarkdownSection> sections = new ArrayList<>();
        String content = section.content();
        int sequence = 1;
        for (int start = 0; start < content.length(); start += MAX_PARENT_CHARS) {
            int end = Math.min(content.length(), start + MAX_PARENT_CHARS);
            sections.add(new MarkdownSection(section.sectionTitle() + "（片段" + sequence + "）", content.substring(start, end).trim()));
            sequence++;
        }
        return sections;
    }

    private List<Document> buildChildDocuments(String ragId,
                                               String docId,
                                               String title,
                                               String source,
                                               String sectionTitle,
                                               String parentChunkId,
                                               String parentText,
                                               int childOrderStart) {
        Document parentDocument = Document.builder()
                .id(docId + ":" + parentChunkId)
                .text(parentText)
                .metadata(Map.of(
                        "rag_id", ragId,
                        "doc_id", docId,
                        "title", title,
                        "source", source,
                        "section_title", sectionTitle,
                        "parent_chunk_id", parentChunkId,
                        "knowledge_tag", ragId
                ))
                .build();

        List<Document> splitDocuments = tokenTextSplitter.apply(List.of(parentDocument));
        List<Document> childDocuments = new ArrayList<>();
        for (int i = 0; i < splitDocuments.size(); i++) {
            Document splitDocument = splitDocuments.get(i);
            int childOrder = childOrderStart + i;
            String chunkId = buildChildChunkId(parentChunkId, i + 1);
            Map<String, Object> metadata = buildChunkMetadata(ragId, docId, title, source,
                    sectionTitle, childOrder, chunkId, parentChunkId, 2, "markdown_child");
            childDocuments.add(Document.builder()
                    .id(docId + ":" + chunkId)
                    .text(splitDocument.getText())
                    .metadata(metadata)
                    .build());
        }
        return childDocuments;
    }

    private Map<String, Object> buildChunkMetadata(String ragId,
                                                   String docId,
                                                   String title,
                                                   String source,
                                                   String sectionTitle,
                                                   int chunkOrder,
                                                   String chunkId,
                                                   String parentChunkId,
                                                   int chunkLevel,
                                                   String chunkType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rag_id", ragId);
        metadata.put("doc_id", docId);
        metadata.put("chunk_id", chunkId);
        metadata.put("parent_chunk_id", parentChunkId);
        metadata.put("chunk_level", chunkLevel);
        metadata.put("chunk_type", chunkType);
        metadata.put("title", title);
        metadata.put("source", source);
        metadata.put("section_title", sectionTitle);
        metadata.put("chunk_order", chunkOrder);
        metadata.put("knowledge_tag", ragId);
        metadata.put("status", ENABLED_STATUS);
        return metadata;
    }

    private String buildSummary(List<MarkdownSection> sections) {
        StringBuilder builder = new StringBuilder();
        for (MarkdownSection section : sections) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(section.sectionTitle()).append("：").append(section.content());
            if (builder.length() >= DOC_SUMMARY_MAX_CHARS) {
                break;
            }
        }
        return trimToLimit(builder.toString(), DOC_SUMMARY_MAX_CHARS);
    }

    private String buildParentText(String sectionTitle, String content) {
        if (StringUtils.isBlank(sectionTitle)) {
            return content;
        }
        return sectionTitle + System.lineSeparator() + System.lineSeparator() + content;
    }

    private String buildParentChunkId(int order) {
        return "p_" + String.format("%03d", order);
    }

    private String buildChildChunkId(String parentChunkId, int order) {
        return parentChunkId + "_c_" + String.format("%03d", order);
    }

    private String firstNonBlankLine(String markdownText) {
        if (StringUtils.isBlank(markdownText)) {
            return "";
        }
        for (String line : markdownText.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (StringUtils.isNotBlank(line)) {
                return line.trim();
            }
        }
        return "";
    }

    private String normalizeSectionBody(String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replaceAll("\\n{3,}", System.lineSeparator() + System.lineSeparator());
        return normalized.trim();
    }

    private String trimToLimit(String text, int limit) {
        if (StringUtils.isBlank(text) || text.length() <= limit) {
            return StringUtils.defaultString(text).trim();
        }
        return text.substring(0, limit).trim();
    }

    private record MarkdownSection(String sectionTitle, String content) {
    }

}
