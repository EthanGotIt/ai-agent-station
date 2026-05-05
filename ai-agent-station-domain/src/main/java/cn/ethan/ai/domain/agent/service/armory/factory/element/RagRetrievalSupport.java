package cn.ethan.ai.domain.agent.service.armory.factory.element;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * RAG 检索辅助服务，负责轻量查询改写与召回结果去重。
 */
public class RagRetrievalSupport {

    private static final int DEFAULT_REWRITE_QUERY_COUNT = 3;

    private static final int DEFAULT_FINGERPRINT_LENGTH = 180;

    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[\\p{Punct}，。！？；：、（）【】《》“”‘’]+");

    private static final List<String> QUERY_NOISE_WORDS = List.of(
            "请", "帮我", "麻烦", "一下", "能否", "能不能", "可以", "关于", "相关", "资料", "文档", "说明", "解释", "总结"
    );

    private static final List<String> QUERY_BOUNDARY_WORDS = List.of(
            "接入", "配置", "实现", "流程", "机制", "架构", "方案", "问题", "错误", "检索", "召回", "去重", "编排"
    );

    private static final List<String> DOCUMENT_ID_KEYS = List.of(
            "doc_id", "document_id", "documentId", "file_id", "source_id"
    );

    private static final List<String> CHUNK_ID_KEYS = List.of(
            "chunk_id", "chunkId", "chunk_index", "chunkIndex", "page", "section"
    );

    private static final String PARENT_CHUNK_ID_KEY = "parent_chunk_id";

    private static final String RETRIEVAL_PARENT_KEY = "qa_parent_key";

    public List<String> rewriteQueries(String userText, int maxRewriteQueries) {
        int effectiveMaxQueries = maxRewriteQueries <= 0 ? DEFAULT_REWRITE_QUERY_COUNT : maxRewriteQueries;
        LinkedHashMap<String, String> queryMap = new LinkedHashMap<>();

        addQuery(queryMap, userText);
        String normalizedQuery = normalizeQuery(userText);
        addQuery(queryMap, normalizedQuery);
        addQuery(queryMap, buildKeywordQuery(normalizedQuery));

        return queryMap.values().stream()
                .filter(StringUtils::isNotBlank)
                .limit(effectiveMaxQueries)
                .toList();
    }

    public List<Document> mergeAndDeduplicate(List<Document> documents, int limit, int fingerprintLength) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? documents.size() : limit;
        int effectiveFingerprintLength = fingerprintLength <= 0 ? DEFAULT_FINGERPRINT_LENGTH : fingerprintLength;

        Map<String, Document> deduplicated = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || StringUtils.isBlank(document.getText())) {
                continue;
            }
            String dedupeKey = dedupeKey(document, effectiveFingerprintLength);
            Document existedDocument = deduplicated.get(dedupeKey);
            if (existedDocument == null || compareScore(document, existedDocument) > 0) {
                deduplicated.put(dedupeKey, document);
            }
        }

        return deduplicated.values().stream()
                .sorted(Comparator.comparing(this::scoreOrZero).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    /**
     * RRF 融合排序（Reciprocal Rank Fusion）。
     */
    public List<Document> rrfFuse(List<List<Document>> routeDocuments, int limit, int rankConstant) {
        if (routeDocuments == null || routeDocuments.isEmpty()) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        int effectiveRankConstant = rankConstant <= 0 ? 60 : rankConstant;
        Map<String, Document> bestDocumentMap = new LinkedHashMap<>();
        Map<String, Double> fusedScoreMap = new LinkedHashMap<>();

        for (List<Document> documents : routeDocuments) {
            if (documents == null || documents.isEmpty()) {
                continue;
            }
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                if (document == null || StringUtils.isBlank(document.getText())) {
                    continue;
                }
                String key = dedupeKey(document, DEFAULT_FINGERPRINT_LENGTH);
                double delta = 1D / (effectiveRankConstant + i + 1D);
                fusedScoreMap.put(key, fusedScoreMap.getOrDefault(key, 0D) + delta);
                Document existed = bestDocumentMap.get(key);
                if (existed == null || compareScore(document, existed) > 0) {
                    bestDocumentMap.put(key, document);
                }
            }
        }

        return bestDocumentMap.entrySet().stream()
                .map(entry -> {
                    Double rrfScore = fusedScoreMap.getOrDefault(entry.getKey(), 0D);
                    return entry.getValue().mutate()
                            .score(rrfScore)
                            .metadata(RETRIEVAL_PARENT_KEY, entry.getKey())
                            .build();
                })
                .sorted(Comparator.comparing(this::scoreOrZero).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    /**
     * 子块命中后向父块回溯（Small-to-Big）。
     */
    public List<Document> expandWithParent(List<Document> fusedDocuments, Function<Document, Document> parentResolver) {
        if (fusedDocuments == null || fusedDocuments.isEmpty()) {
            return List.of();
        }
        if (parentResolver == null) {
            return fusedDocuments;
        }

        List<Document> expanded = new ArrayList<>();
        for (Document document : fusedDocuments) {
            if (document == null) {
                continue;
            }
            Document parentDocument = parentResolver.apply(document);
            if (parentDocument == null) {
                expanded.add(document);
                continue;
            }

            Map<String, Object> mergedMetadata = new LinkedHashMap<>();
            if (parentDocument.getMetadata() != null) {
                mergedMetadata.putAll(parentDocument.getMetadata());
            }
            if (document.getMetadata() != null) {
                mergedMetadata.putAll(document.getMetadata());
            }
            mergedMetadata.put("qa_hit_chunk_id", metadataKey(document, List.of("chunk_id", "chunkId")));
            mergedMetadata.put("qa_parent_chunk_id", metadataKey(parentDocument, List.of("chunk_id", "chunkId")));
            mergedMetadata.put("qa_hit_text", document.getText());
            mergedMetadata.put(RETRIEVAL_PARENT_KEY, resolveParentKey(parentDocument, document));

            expanded.add(parentDocument.mutate()
                    .score(scoreOrZero(document))
                    .metadata(mergedMetadata)
                    .build());
        }
        return expanded;
    }

    /**
     * 父级去重，命中同一父块只保留最高分结果。
     */
    public List<Document> deduplicateByParent(List<Document> documents, int limit) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? documents.size() : limit;
        Map<String, Document> grouped = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || StringUtils.isBlank(document.getText())) {
                continue;
            }
            String parentKey = resolveParentKey(document, null);
            Document existed = grouped.get(parentKey);
            if (existed == null || compareScore(document, existed) > 0) {
                grouped.put(parentKey, document);
            }
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing(this::scoreOrZero).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    public String formatEvidenceContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            builder.append("[证据").append(i + 1).append("] ");
            builder.append("来源：").append(resolveSource(document)).append(System.lineSeparator());
            if (document.getMetadata() != null) {
                Object retrievalQuery = document.getMetadata().get("qa_retrieval_query");
                if (retrievalQuery != null && StringUtils.isNotBlank(retrievalQuery.toString())) {
                    builder.append("召回Query：").append(retrievalQuery).append(System.lineSeparator());
                }
            }
            if (document.getScore() != null) {
                builder.append("融合分数：").append(document.getScore()).append(System.lineSeparator());
            }
            builder.append(document.getText()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private void addQuery(Map<String, String> queryMap, String query) {
        if (StringUtils.isBlank(query)) {
            return;
        }
        String normalizedKey = query.trim().toLowerCase(Locale.ROOT);
        queryMap.putIfAbsent(normalizedKey, query.trim());
    }

    private String normalizeQuery(String userText) {
        if (StringUtils.isBlank(userText)) {
            return "";
        }
        String normalized = PUNCTUATION_PATTERN.matcher(userText).replaceAll(" ");
        for (String noiseWord : QUERY_NOISE_WORDS) {
            normalized = normalized.replace(noiseWord, " ");
        }
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String buildKeywordQuery(String normalizedQuery) {
        if (StringUtils.isBlank(normalizedQuery)) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        for (String token : normalizedQuery.split("\\s+")) {
            if (StringUtils.isBlank(token)) {
                continue;
            }
            String trimmedToken = token.trim();
            if (trimmedToken.length() >= 2 && !QUERY_NOISE_WORDS.contains(trimmedToken)) {
                tokens.add(trimmedToken);
            }
        }
        if (tokens.isEmpty()) {
            return normalizedQuery;
        }
        String keywordQuery = String.join(" ", tokens);
        if (!keywordQuery.equalsIgnoreCase(normalizedQuery)) {
            return keywordQuery;
        }
        String expandedQuery = normalizedQuery;
        for (String boundaryWord : QUERY_BOUNDARY_WORDS) {
            expandedQuery = expandedQuery.replace(boundaryWord, " " + boundaryWord + " ");
        }
        return expandedQuery.replaceAll("\\s+", " ").trim();
    }

    private String dedupeKey(Document document, int fingerprintLength) {
        String documentKey = metadataKey(document, DOCUMENT_ID_KEYS);
        String chunkKey = metadataKey(document, CHUNK_ID_KEYS);
        if (StringUtils.isNotBlank(documentKey) && StringUtils.isNotBlank(chunkKey)) {
            return "chunk:" + documentKey + ":" + chunkKey;
        }
        String contentFingerprint = contentFingerprint(document.getText(), fingerprintLength);
        if (StringUtils.isNotBlank(contentFingerprint)) {
            return "content:" + contentFingerprint;
        }
        return "id:" + document.getId();
    }

    private String resolveParentKey(Document parentOrSelf, Document fallbackChild) {
        if (parentOrSelf != null && parentOrSelf.getMetadata() != null) {
            Object value = parentOrSelf.getMetadata().get(RETRIEVAL_PARENT_KEY);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString();
            }
        }
        String documentId = metadataKey(parentOrSelf, DOCUMENT_ID_KEYS);
        String parentChunkId = metadataKey(parentOrSelf, List.of(PARENT_CHUNK_ID_KEY, "parentChunkId"));
        String chunkId = metadataKey(parentOrSelf, CHUNK_ID_KEYS);
        if (StringUtils.isBlank(chunkId) && fallbackChild != null) {
            chunkId = metadataKey(fallbackChild, CHUNK_ID_KEYS);
        }
        String effectiveChunkId = StringUtils.defaultIfBlank(parentChunkId, chunkId);
        if (StringUtils.isNotBlank(documentId) && StringUtils.isNotBlank(effectiveChunkId)) {
            return "parent:" + documentId + ":" + effectiveChunkId;
        }
        if (StringUtils.isNotBlank(documentId)) {
            return "parent:" + documentId;
        }
        return "parent:" + StringUtils.defaultIfBlank(parentOrSelf == null ? null : parentOrSelf.getId(), "unknown");
    }

    private String metadataKey(Document document, List<String> keys) {
        if (document == null || document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = document.getMetadata().get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString().trim();
            }
        }
        return "";
    }

    private String contentFingerprint(String text, int fingerprintLength) {
        String normalizedText = PUNCTUATION_PATTERN.matcher(Objects.toString(text, ""))
                .replaceAll("")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        if (normalizedText.length() <= fingerprintLength) {
            return normalizedText;
        }
        return normalizedText.substring(0, fingerprintLength);
    }

    private String resolveSource(Document document) {
        if (document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return StringUtils.defaultIfBlank(document.getId(), "未知文档");
        }
        for (String key : List.of("source", "file_name", "filename", "title", "doc_id", "document_id")) {
            Object value = document.getMetadata().get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString();
            }
        }
        return StringUtils.defaultIfBlank(document.getId(), "未知文档");
    }

    private int compareScore(Document left, Document right) {
        return Double.compare(scoreOrZero(left), scoreOrZero(right));
    }

    private double scoreOrZero(Document document) {
        return document.getScore() == null ? 0 : document.getScore();
    }

}
