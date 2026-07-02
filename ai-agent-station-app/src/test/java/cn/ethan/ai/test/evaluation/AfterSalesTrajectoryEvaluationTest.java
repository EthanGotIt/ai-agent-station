package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.service.AfterSalesGraphRuntime;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class AfterSalesTrajectoryEvaluationTest {

    @Test
    void frozenThirtyCaseSetShouldKeepGovernedRoutesDeterministic() throws Exception {
        List<JSONObject> cases = loadCases();
        Assertions.assertEquals(30, cases.size());
        Assertions.assertEquals(12, categoryCount(cases, "NORMAL"));
        Assertions.assertEquals(8, categoryCount(cases, "RULE"));
        Assertions.assertEquals(10, categoryCount(cases, "ERROR"));

        AfterSalesGraphRuntime graphRuntime = new AfterSalesGraphRuntime();
        List<String> failures = new ArrayList<>();
        for (JSONObject testCase : cases) {
            AfterSalesAgentState state = graphRuntime.execute(toInput(testCase), testCase.getString("id"));
            String expected = testCase.getString("expectedStage");
            if (!expected.equals(state.stage().name())) {
                failures.add(testCase.getString("id") + ": expected=" + expected + ", actual=" + state.stage());
            }
        }
        Assertions.assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private List<JSONObject> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/after-sales-trajectory-v1.jsonl");
        Assertions.assertNotNull(stream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(JSON::parseObject)
                    .toList();
        }
    }

    private long categoryCount(List<JSONObject> cases, String category) {
        return cases.stream().filter(item -> category.equals(item.getString("category"))).count();
    }

    private Map<String, Object> toInput(JSONObject testCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        putText(input, AfterSalesAgentState.USER_ID, testCase.getString("userId"));
        putText(input, AfterSalesAgentState.ORDER_ID, testCase.getString("orderId"));
        putText(input, AfterSalesAgentState.ORDER_OWNER_ID, testCase.getString("ownerId"));
        putText(input, AfterSalesAgentState.ORDER_STATUS, testCase.getString("status"));
        putText(input, AfterSalesAgentState.REFUND_REASON, testCase.getString("reason"));
        putText(input, AfterSalesAgentState.ERROR_TYPE, testCase.getString("errorType"));
        putNumber(testCase, "days", value -> input.put(AfterSalesAgentState.DAYS_SINCE_DELIVERY, value));
        putNumber(testCase, "repairCount", value -> input.put(AfterSalesAgentState.REPAIR_COUNT, value));
        putNumber(testCase, "retryCount", value -> input.put(AfterSalesAgentState.RETRY_COUNT, value));
        putNumber(testCase, "reloadCount", value -> input.put(AfterSalesAgentState.RELOAD_COUNT, value));
        if (testCase.containsKey("sameFailureRepeated")) {
            input.put(AfterSalesAgentState.SAME_FAILURE_REPEATED,
                    testCase.getBooleanValue("sameFailureRepeated"));
        }
        return input;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putNumber(JSONObject source, String key, Consumer<Integer> consumer) {
        if (source.containsKey(key)) {
            consumer.accept(source.getInteger(key));
        }
    }
}
