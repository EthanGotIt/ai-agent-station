package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentHarnessExecuteService;
import cn.ethan.ai.domain.agent.service.rag.RagIngestionService;
import cn.ethan.ai.rag.PgVectorEvidenceRetrievalPort;
import cn.ethan.ai.test.support.ManualTestGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RagLiveEvaluationIT {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(E\\d+)]");
    private static final String DATASET = "/evaluation/rag-evaluation-v1.jsonl";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Resource
    private AgentHarnessExecuteService harnessExecuteService;

    @Resource
    private RagIngestionService ingestionService;

    @Resource
    private PgVectorEvidenceRetrievalPort adaptiveRetrieval;

    @Resource(name = "vectorStore")
    private VectorStore vectorStore;

    @Resource(name = "mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Resource(name = "pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    private RagEvaluationCorpusManager corpusManager;

    @BeforeAll
    void setUpLiveEvaluation() throws Exception {
        ManualTestGate.requireRealAi("RagLiveEvaluationIT");
        ManualTestGate.requireDbMutation("RagLiveEvaluationIT");
        Assumptions.assumeTrue(Boolean.parseBoolean(readSetting("RUN_LIVE_RAG_EVALUATION")),
                "完整 live evaluation 默认跳过；显式设置 RUN_LIVE_RAG_EVALUATION=true 后执行。");
        corpusManager = new RagEvaluationCorpusManager(ingestionService, adaptiveRetrieval,
                vectorStore, mysqlJdbcTemplate, pgVectorJdbcTemplate);
        corpusManager.seed();
    }

    @AfterAll
    void tearDownEvaluationCorpus() {
        if (corpusManager != null) {
            corpusManager.cleanup();
        }
    }

    @Test
    void runThreeModeLiveEvaluation() throws Exception {
        List<RagEvaluationSupport.EvaluationCase> cases = selectedCases(RagEvaluationSupport.loadCases());
        Map<RagEvaluationSupport.RetrievalMode, List<RagEvaluationSupport.EvaluationResult>> results = new LinkedHashMap<>();
        results.put(RagEvaluationSupport.RetrievalMode.PGVECTOR_ONLY,
                evaluateLocalBaseline(cases, RagEvaluationSupport.RetrievalMode.PGVECTOR_ONLY));
        results.put(RagEvaluationSupport.RetrievalMode.FIXED_ADVANCED_RAG_BASELINE,
                evaluateLocalBaseline(cases, RagEvaluationSupport.RetrievalMode.FIXED_ADVANCED_RAG_BASELINE));

        List<RagEvaluationSupport.EvaluationResult> adaptive = new ArrayList<>();
        for (RagEvaluationSupport.EvaluationCase evaluationCase : cases) {
            adaptive.add(evaluateAdaptive(evaluationCase));
            System.out.printf("[live-eval] adaptive %s/%s completed%n", adaptive.size(), cases.size());
        }
        adaptive = applyAnswerQualityJudge(adaptive, cases);
        results.put(RagEvaluationSupport.RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL, adaptive);

        Map<String, Object> retention = retentionSignals(results);
        String datasetHash = datasetHash();
        String model = StringUtils.defaultIfBlank(readSetting("RAG_EVAL_MODEL"), "qwen3.7-max");
        new RagLiveEvaluationReportWriter().write(results, retention, datasetHash, model);

        List<RagEvaluationSupport.EvaluationResult> technicalFailures = adaptive.stream()
                .filter(result -> StringUtils.isNotBlank(result.error())).toList();
        Assertions.assertTrue(technicalFailures.isEmpty(),
                () -> "Adaptive live evaluation 存在技术失败：" + technicalFailures.stream()
                        .map(result -> result.id() + "=" + result.error()).collect(Collectors.joining("; ")));
        if (cases.size() < RagEvaluationSupport.loadCases().size()) {
            return;
        }
        RagEvaluationSupport.Metrics metrics = RagEvaluationSupport.calculate(adaptive);
        Assertions.assertTrue(metrics.sourceRoutingAccuracy() >= 0.90D, "来源路由准确率未达到 90%");
        Assertions.assertTrue(metrics.hitAt5() >= 0.85D, "Hit@5 未达到 85%");
        Assertions.assertTrue(metrics.refusalF1() >= 0.90D, "拒答 F1 未达到 0.90");
        Assertions.assertEquals(1D, metrics.citationValidity(), 0.0001D, "引用有效率必须为 100%");
        Assertions.assertTrue(metrics.keyPointCoverage() >= 0.80D, "关键点覆盖率未达到 80%");
        Assertions.assertEquals(Boolean.FALSE, retention.get("bm25GatePassed"),
                "BM25 对照达到保留门槛，不应删除生产实现");
        Assertions.assertEquals(Boolean.TRUE, retention.get("secondRetrievalGatePassed"),
                "二次检索未达到保留门槛");
    }

    private List<RagEvaluationSupport.EvaluationResult> evaluateLocalBaseline(
            List<RagEvaluationSupport.EvaluationCase> cases,
            RagEvaluationSupport.RetrievalMode mode) {
        List<RagEvaluationSupport.EvaluationResult> results = new ArrayList<>();
        for (RagEvaluationSupport.EvaluationCase evaluationCase : cases.stream()
                .filter(RagEvaluationSupport.EvaluationCase::isLocalRetrievalCase).toList()) {
            long start = System.currentTimeMillis();
            List<Document> documents = corpusManager.retrieve(mode, evaluationCase.question());
            int rank = relevantRank(documents, evaluationCase.acceptableEvidence());
            List<Document> relevant = relevantDocuments(documents, evaluationCase.acceptableEvidence());
            double coverage = keyPointCoverage(evaluationCase.answerKeyPoints(),
                    relevant.stream().map(Document::getText).collect(Collectors.joining("\n")), rank == 0);
            results.add(new RagEvaluationSupport.EvaluationResult(
                    evaluationCase.id(), mode, true, rank, requiresRetrieval(evaluationCase),
                    evaluationCase.shouldRefuse(), rank == 0, rank > 0, coverage, rank > 0 ? 1D : 0D,
                    0, System.currentTimeMillis() - start, false, false,
                    List.of("PROJECT_KNOWLEDGE"), evidenceNames(documents), evidencePreviews(documents),
                    "", ""));
        }
        return results;
    }

    private RagEvaluationSupport.EvaluationResult evaluateAdaptive(RagEvaluationSupport.EvaluationCase evaluationCase) {
        long start = System.currentTimeMillis();
        String sessionId = "rag-eval-" + evaluationCase.id().toLowerCase(Locale.ROOT) + "-" + System.nanoTime();
        try {
            seedMemoryScenario(evaluationCase, sessionId);
            CapturingStream stream = executeHarness(evaluationCase.question(), sessionId);
            String answer = stream.finalAnswer();
            List<AgenticRagTraceVO> traces = stream.traces();
            AgenticRagTraceVO finalTrace = traces.isEmpty() ? new AgenticRagTraceVO() : traces.get(traces.size() - 1);
            List<RagEvidenceVO> evidences = finalTrace.getFinalEvidences() == null
                    ? List.of() : finalTrace.getFinalEvidences();
            List<String> selectedSources = finalTrace.getRetrievalRounds() == null ? List.of()
                    : finalTrace.getRetrievalRounds().stream().map(AgenticRagTraceVO.RetrievalRoundVO::getSourceType)
                    .filter(StringUtils::isNotBlank).distinct().toList();
            int rank = relevantEvidenceRank(evidences, evaluationCase.acceptableEvidence());
            double coverage = keyPointCoverage(evaluationCase.answerKeyPoints(), answer,
                    evaluationCase.shouldRefuse() && isRefusal(answer));
            boolean sessionHit = evaluationCase.acceptableEvidence().stream().anyMatch(item -> item.startsWith("session:"))
                    && coverage > 0D;
            if (rank == 0 && sessionHit) {
                rank = 1;
            }
            boolean secondTriggered = finalTrace.getRetrievalRounds() != null
                    && finalTrace.getRetrievalRounds().size() > 1;
            List<String> firstRoundSources = traces.isEmpty() ? List.of()
                    : traceSources(traces.get(0));
            boolean relevanceRecovered = secondTriggered && !traces.isEmpty()
                    && relevantEvidenceRank(safeEvidences(traces.get(0)), evaluationCase.acceptableEvidence()) == 0
                    && rank > 0;
            boolean sourceCoverageRecovered = secondTriggered
                    && !sourceRouteCorrect(firstRoundSources, evaluationCase.expectedSourceTypes())
                    && sourceRouteCorrect(selectedSources, evaluationCase.expectedSourceTypes());
            boolean secondRecovered = relevanceRecovered || sourceCoverageRecovered;
            boolean refused = isRefusal(answer);
            boolean citationsValid = citationsValid(answer, evidences,
                    !requiresRetrieval(evaluationCase));
            int externalRounds = (int) selectedSources.stream().filter(source -> !"PROJECT_KNOWLEDGE".equals(source)).count();
            int observations = stream.observationCount();
            int modelCalls = observations + externalRounds + (refused ? 0 : 1);
            return new RagEvaluationSupport.EvaluationResult(
                    evaluationCase.id(), RagEvaluationSupport.RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL,
                    !requiresRetrieval(evaluationCase)
                            || sourceRouteCorrect(selectedSources, evaluationCase.expectedSourceTypes()), rank,
                    requiresRetrieval(evaluationCase), evaluationCase.shouldRefuse(), refused,
                    citationsValid, coverage, 0D, modelCalls, System.currentTimeMillis() - start,
                    secondTriggered, secondRecovered,
                    selectedSources,
                    evidences.stream().map(this::evidenceName).toList(),
                    evidences.stream().map(RagEvidenceVO::getContentPreview).filter(StringUtils::isNotBlank).toList(),
                    answer, "");
        } catch (Exception e) {
            return new RagEvaluationSupport.EvaluationResult(
                    evaluationCase.id(), RagEvaluationSupport.RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL,
                    false, 0, requiresRetrieval(evaluationCase), evaluationCase.shouldRefuse(), false,
                    false, evaluationCase.answerKeyPoints().isEmpty() ? -1D : 0D, -1D, 0,
                    System.currentTimeMillis() - start, false, false,
                    List.of(), List.of(), List.of(), "", rootMessage(e));
        }
    }

    private CapturingStream executeHarness(String message, String sessionId) throws Exception {
        CapturingStream stream = new CapturingStream();
        harnessExecuteService.execute(ExecuteCommandEntity.builder()
                .aiAgentId("1")
                .sessionId(sessionId)
                .message(message)
                .maxStep(4)
                .streamProtocol("streamable_http")
                .build(), stream);
        return stream;
    }

    private void seedMemoryScenario(RagEvaluationSupport.EvaluationCase evaluationCase, String sessionId) throws Exception {
        List<String> setupTurns = switch (evaluationCase.id()) {
            case "MF01" -> List.of("请根据项目知识列出当前三个高层动作。");
            case "MF02" -> List.of(
                    "请根据项目知识列出当前三个高层动作。",
                    "后续回答请保持中文简洁列表格式。"
            );
            case "MF03" -> List.of("请按顺序说明项目的三类 evidence source。");
            case "MF04" -> List.of("请说明项目如何做引用存在性校验，以及校验失败后的纠正次数。");
            case "MF05" -> List.of("请说明 Session 上下文为什么只保留成功完整 Turn，以及如何处理失败孤立 USER。");
            default -> List.of();
        };
        for (String setupTurn : setupTurns) {
            executeHarness(setupTurn, sessionId);
        }
    }

    private List<RagEvaluationSupport.EvaluationResult> applyAnswerQualityJudge(
            List<RagEvaluationSupport.EvaluationResult> source,
            List<RagEvaluationSupport.EvaluationCase> cases) throws Exception {
        Map<String, RagEvaluationSupport.EvaluationCase> caseById = cases.stream()
                .collect(Collectors.toMap(RagEvaluationSupport.EvaluationCase::id, item -> item));
        Map<String, JudgeScore> scores = new LinkedHashMap<>();
        List<RagEvaluationSupport.EvaluationResult> judgeable = source.stream()
                .filter(result -> StringUtils.isBlank(result.error()))
                .toList();
        for (int offset = 0; offset < judgeable.size(); offset += 5) {
            List<RagEvaluationSupport.EvaluationResult> batch = judgeable.subList(offset,
                    Math.min(offset + 5, judgeable.size()));
            scores.putAll(judgeAnswerQuality(batch, caseById));
        }
        List<RagEvaluationSupport.EvaluationResult> result = new ArrayList<>();
        for (RagEvaluationSupport.EvaluationResult item : source) {
            RagEvaluationSupport.EvaluationCase evaluationCase = caseById.get(item.id());
            JudgeScore score = scores.get(item.id());
            boolean refused = score == null ? item.refused() : score.refused();
            double coverage = evaluationCase == null || evaluationCase.answerKeyPoints().isEmpty()
                    ? -1D : score == null ? item.keyPointCoverage() : score.keyPointCoverage();
            double faithfulness = item.evidencePreviews().isEmpty() || refused
                    ? -1D : score == null ? item.faithfulness() : score.faithfulness();
            result.add(new RagEvaluationSupport.EvaluationResult(
                    item.id(), item.mode(), item.sourceRouteCorrect(), item.firstRelevantRank(), item.retrievalExpected(),
                    item.shouldRefuse(), refused, item.citationsValid(), coverage, faithfulness,
                    item.modelCalls(), item.latencyMillis(), item.secondRetrievalTriggered(),
                    item.secondRetrievalRecovered(),
                    item.selectedSources(), item.evidenceSources(), item.evidencePreviews(), item.answer(), item.error()));
        }
        return result;
    }

    private Map<String, JudgeScore> judgeAnswerQuality(
            List<RagEvaluationSupport.EvaluationResult> batch,
            Map<String, RagEvaluationSupport.EvaluationCase> caseById) throws Exception {
        String apiKey = readSetting("OPENAI_API_KEY");
        String model = StringUtils.defaultIfBlank(readSetting("RAG_EVAL_JUDGE_MODEL"), "qwen3.7-max");
        List<Map<String, Object>> judgeCases = batch.stream().map(item -> {
            RagEvaluationSupport.EvaluationCase evaluationCase = caseById.get(item.id());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", item.id());
            data.put("question", evaluationCase == null ? "" : evaluationCase.question());
            data.put("answer", item.answer());
            data.put("expectedKeyPoints", evaluationCase == null ? List.of() : evaluationCase.answerKeyPoints());
            data.put("evidence", item.evidencePreviews());
            return data;
        }).toList();
        String prompt = """
                你是 RAG 回答质量评测器。对每个 id 评估：
                1. refused：回答是否因缺少证据/上下文而拒答或只追问。仅在正文中介绍“无法确认”机制不算拒答。
                2. keyPointCoverage：answer 语义上覆盖 expectedKeyPoints 的比例，范围 0 到 1。
                3. faithfulness：answer 中可核验事实被 evidence 支持的程度，范围 0 到 1；evidence 为空时返回 0。
                只输出 JSON：
                {"scores":{"CASE_ID":{"refused":false,"keyPointCoverage":0.0,"faithfulness":0.0}}}
                待评测数据：
                %s
                """.formatted(mapper.writeValueAsString(judgeCases));
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 1400,
                "enable_thinking", false
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Faithfulness judge HTTP " + response.statusCode());
        }
        String content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        JsonNode scores = mapper.readTree(extractJson(content)).path("scores");
        Map<String, JudgeScore> result = new LinkedHashMap<>();
        scores.fields().forEachRemaining(entry -> result.put(entry.getKey(), new JudgeScore(
                clamp(entry.getValue().path("keyPointCoverage").asDouble()),
                clamp(entry.getValue().path("faithfulness").asDouble()),
                entry.getValue().path("refused").asBoolean(false)
        )));
        return result;
    }

    private Map<String, Object> retentionSignals(
            Map<RagEvaluationSupport.RetrievalMode, List<RagEvaluationSupport.EvaluationResult>> results) {
        List<RagEvaluationSupport.EvaluationResult> pg = results.get(RagEvaluationSupport.RetrievalMode.PGVECTOR_ONLY);
        List<RagEvaluationSupport.EvaluationResult> fixed = results.get(RagEvaluationSupport.RetrievalMode.FIXED_ADVANCED_RAG_BASELINE);
        Map<String, RagEvaluationSupport.EvaluationResult> pgById = pg.stream()
                .collect(Collectors.toMap(RagEvaluationSupport.EvaluationResult::id, item -> item));
        List<RagEvaluationSupport.EvaluationResult> exactFixed = fixed.stream().filter(item -> item.id().startsWith("ET")).toList();
        List<RagEvaluationSupport.EvaluationResult> exactPg = pg.stream().filter(item -> item.id().startsWith("ET")).toList();
        double hitDelta = RagEvaluationSupport.calculate(exactFixed).hitAt5()
                - RagEvaluationSupport.calculate(exactPg).hitAt5();
        long repairs = exactFixed.stream().filter(item -> item.firstRelevantRank() > 0
                && pgById.get(item.id()).firstRelevantRank() == 0).count();
        List<RagEvaluationSupport.EvaluationResult> adaptive = results.get(
                RagEvaluationSupport.RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL);
        long secondTriggered = adaptive.stream().filter(RagEvaluationSupport.EvaluationResult::secondRetrievalTriggered).count();
        long secondRecovered = adaptive.stream().filter(RagEvaluationSupport.EvaluationResult::secondRetrievalRecovered).count();
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("bm25ExactHitAt5Delta", hitDelta);
        signals.put("bm25IndependentRepairs", repairs);
        boolean bm25GatePassed = hitDelta >= 0.10D || repairs >= 3;
        signals.put("bm25GatePassed", bm25GatePassed);
        signals.put("bm25Retained", bm25GatePassed);
        signals.put("smallToBigRetained", false);
        signals.put("secondRetrievalTriggeredCases", secondTriggered);
        signals.put("secondRetrievalRecoveredCases", secondRecovered);
        signals.put("secondRetrievalRecoveryRate", secondTriggered == 0 ? 0D : secondRecovered / (double) secondTriggered);
        signals.put("secondRetrievalGatePassed", secondRecovered >= 3
                && secondTriggered > 0 && secondRecovered / (double) secondTriggered >= 0.20D);
        return signals;
    }

    private int relevantRank(List<Document> documents, List<String> acceptable) {
        if (documents == null || acceptable == null || acceptable.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String haystack = normalize(metadata(document, "source") + " " + metadata(document, "title")
                    + " " + document.getText());
            if (acceptable.stream().map(this::normalize).anyMatch(haystack::contains)) {
                return index + 1;
            }
        }
        return 0;
    }

    private int relevantEvidenceRank(List<RagEvidenceVO> evidences, List<String> acceptable) {
        if (evidences == null || acceptable == null || acceptable.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < evidences.size(); index++) {
            RagEvidenceVO evidence = evidences.get(index);
            String haystack = normalize(evidenceName(evidence) + " " + evidence.getContentPreview());
            if (acceptable.stream().map(this::normalize).anyMatch(haystack::contains)) {
                return index + 1;
            }
        }
        return 0;
    }

    private List<Document> relevantDocuments(List<Document> documents, List<String> acceptable) {
        if (documents == null || acceptable == null) {
            return List.of();
        }
        return documents.stream().filter(document -> {
            String haystack = normalize(metadata(document, "source") + " " + metadata(document, "title"));
            return acceptable.stream().map(this::normalize).anyMatch(haystack::contains);
        }).toList();
    }

    private boolean sourceRouteCorrect(List<String> actual, List<String> expected) {
        return RagEvaluationSupport.sourceRouteCorrect(actual, expected);
    }

    private boolean citationsValid(String answer, List<RagEvidenceVO> evidences, boolean citationOptional) {
        Set<String> available = evidences == null ? Set.of() : evidences.stream()
                .map(RagEvidenceVO::getEvidenceId).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        Matcher matcher = CITATION_PATTERN.matcher(StringUtils.defaultString(answer));
        Set<String> cited = new LinkedHashSet<>();
        while (matcher.find()) {
            cited.add(matcher.group(1));
        }
        return cited.stream().allMatch(available::contains) && (citationOptional || !cited.isEmpty());
    }

    private boolean isRefusal(String answer) {
        String normalized = normalize(answer);
        return List.of("证据不足", "无法确认", "无法从", "不能提供", "不能证明", "无法证明")
                .stream().map(this::normalize).anyMatch(normalized::contains);
    }

    private double keyPointCoverage(List<String> keyPoints, String content, boolean correctEmptyCase) {
        if (keyPoints == null || keyPoints.isEmpty()) {
            return -1D;
        }
        String normalized = normalize(content);
        long hits = keyPoints.stream().map(this::normalize).filter(normalized::contains).count();
        return hits / (double) keyPoints.size();
    }

    private List<String> evidenceNames(List<Document> documents) {
        return documents.stream().map(document -> StringUtils.defaultIfBlank(
                metadata(document, "source"), metadata(document, "title"))).toList();
    }

    private List<String> evidencePreviews(List<Document> documents) {
        return documents.stream().map(Document::getText).filter(StringUtils::isNotBlank)
                .map(content -> content.length() <= 500 ? content : content.substring(0, 500)).toList();
    }

    private String evidenceName(RagEvidenceVO evidence) {
        return String.join(" ", StringUtils.defaultString(evidence.getSourceName()),
                StringUtils.defaultString(evidence.getUri()), StringUtils.defaultString(evidence.getToolName()));
    }

    private String metadata(Document document, String key) {
        Object value = document == null ? null : document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private boolean metadataBoolean(Document document, String key) {
        return Boolean.parseBoolean(metadata(document, key));
    }

    private boolean requiresRetrieval(RagEvaluationSupport.EvaluationCase evaluationCase) {
        return evaluationCase.acceptableEvidence().stream()
                .anyMatch(item -> !item.startsWith("session:"));
    }

    private List<RagEvidenceVO> safeEvidences(AgenticRagTraceVO trace) {
        return trace == null || trace.getFinalEvidences() == null ? List.of() : trace.getFinalEvidences();
    }

    private List<String> traceSources(AgenticRagTraceVO trace) {
        return trace == null || trace.getRetrievalRounds() == null ? List.of()
                : trace.getRetrievalRounds().stream()
                .map(AgenticRagTraceVO.RetrievalRoundVO::getSourceType)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s`'\"，。；：、()（）\\[\\]{}]", "");
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return StringUtils.defaultIfBlank(cursor.getMessage(), cursor.getClass().getSimpleName());
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Judge 未返回 JSON");
        }
        return content.substring(start, end + 1);
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private String datasetHash() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = java.util.Objects.requireNonNull(getClass().getResourceAsStream(DATASET))) {
            return HexFormat.of().withUpperCase().formatHex(digest.digest(input.readAllBytes()));
        }
    }

    private String readSetting(String key) {
        String property = System.getProperty(key);
        return StringUtils.isNotBlank(property) ? property : System.getenv(key);
    }

    private List<RagEvaluationSupport.EvaluationCase> selectedCases(
            List<RagEvaluationSupport.EvaluationCase> allCases) {
        String configured = readSetting("RAG_EVAL_CASE_IDS");
        if (StringUtils.isBlank(configured)) {
            return allCases;
        }
        Set<String> selected = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        return allCases.stream().filter(item -> selected.contains(item.id())).toList();
    }

    private static final class CapturingStream implements IAgentStreamPort {

        private final List<AgentExecuteResultEntity> events = new ArrayList<>();

        @Override
        public void send(AgentExecuteResultEntity result) {
            events.add(result);
        }

        @Override
        public void complete() {
        }

        @Override
        public void onTimeout(Runnable callback) {
        }

        @Override
        public void onCompletion(Runnable callback) {
        }

        String finalAnswer() {
            return events.stream().filter(event -> "summary".equals(event.getType()))
                    .map(AgentExecuteResultEntity::getContent).filter(StringUtils::isNotBlank)
                    .reduce((first, second) -> second).orElse("");
        }

        List<AgenticRagTraceVO> traces() {
            return events.stream().filter(event -> "rag_evidence".equals(event.getSubType()))
                    .map(AgentExecuteResultEntity::getPayload)
                    .filter(AgenticRagTraceVO.class::isInstance)
                    .map(AgenticRagTraceVO.class::cast).toList();
        }

        int observationCount() {
            return (int) events.stream().filter(event -> "harness_observation".equals(event.getSubType()))
                    .map(AgentExecuteResultEntity::getPayload).filter(HarnessObservationVO.class::isInstance).count();
        }
    }

    private record JudgeScore(double keyPointCoverage, double faithfulness, boolean refused) {
    }
}
