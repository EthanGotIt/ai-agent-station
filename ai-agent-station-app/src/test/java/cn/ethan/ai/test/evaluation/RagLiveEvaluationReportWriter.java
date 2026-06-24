package cn.ethan.ai.test.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RagLiveEvaluationReportWriter {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    Path write(Map<RagEvaluationSupport.RetrievalMode, List<RagEvaluationSupport.EvaluationResult>> results,
               Map<String, Object> retention,
               String datasetHash,
               String model) throws Exception {
        Path outputDir = Path.of(System.getProperty("evaluation.output", "target/evaluation")).toAbsolutePath();
        Files.createDirectories(outputDir);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", OffsetDateTime.now().toString());
        payload.put("datasetHash", datasetHash);
        payload.put("model", model);
        Map<String, Object> modes = new LinkedHashMap<>();
        results.forEach((mode, modeResults) -> modes.put(mode.name(), Map.of(
                "metrics", RagEvaluationSupport.calculate(modeResults),
                "results", modeResults
        )));
        payload.put("modes", modes);
        payload.put("retention", retention);

        Path json = outputDir.resolve("rag-evaluation-v1-live.json");
        mapper.writeValue(json.toFile(), payload);
        Files.writeString(outputDir.resolve("rag-evaluation-v1-live.md"),
                markdown(results, retention, datasetHash, model), StandardCharsets.UTF_8);
        return json;
    }

    private String markdown(Map<RagEvaluationSupport.RetrievalMode, List<RagEvaluationSupport.EvaluationResult>> results,
                            Map<String, Object> retention,
                            String datasetHash,
                            String model) {
        StringBuilder text = new StringBuilder("# RAG Evaluation V1 Live Report\n\n");
        text.append("- Dataset SHA-256: `").append(datasetHash).append("`\n");
        text.append("- Model: `").append(model).append("`\n");
        text.append("- Generated at: `").append(OffsetDateTime.now()).append("`\n");
        text.append("- Baselines are retrieval-only comparisons over the 25 local project cases.\n");
        text.append("- Adaptive mode is an end-to-end live Harness run over all 60 cases.\n\n");
        text.append("| Mode | Cases | Route | Hit@5 | MRR | Refusal F1 | Citation | Key points | Faithfulness | Avg calls | P95 ms |\n");
        text.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        results.forEach((mode, modeResults) -> {
            RagEvaluationSupport.Metrics metrics = RagEvaluationSupport.calculate(modeResults);
            text.append("|").append(mode.name()).append("|")
                    .append(metrics.caseCount()).append("|")
                    .append(percent(metrics.sourceRoutingAccuracy())).append("|")
                    .append(percent(metrics.hitAt5())).append("|")
                    .append(decimal(metrics.mrr())).append("|")
                    .append(percent(metrics.refusalF1())).append("|")
                    .append(percent(metrics.citationValidity())).append("|")
                    .append(percent(metrics.keyPointCoverage())).append("|")
                    .append(percent(metrics.faithfulness())).append("|")
                    .append(decimal(metrics.averageModelCalls())).append("|")
                    .append(metrics.p95LatencyMillis()).append("|\n");
        });
        text.append("\n## Retention Signals\n\n");
        retention.forEach((key, value) -> text.append("- `").append(key).append("`: ").append(value).append("\n"));
        text.append("\nThe JSON report contains every case result and error for reproducibility.\n");
        return text.toString();
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100D);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
