package cn.ethan.core.agent.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 结果模型测试：验证来源协议、数量和输出安全边界。
 *
 * @author ethan
 * @date 2026-08-05
 */
class ReActResultModelTest {

    @Test
    void dropsUnsafeAndExcessiveSources() {
        List<String> sources = new ArrayList<>(List.of(
                "javascript:alert(1)",
                "data:text/plain,unsafe",
                "https://user:password@example.com/private",
                "https://example.com/valid",
                "https://example.com/valid"
        ));
        for (int index = 0; index < 25; index++) {
            sources.add("https://example.com/source-" + index);
        }

        ReActResultModel result = new ReActResultModel(
                "answer",
                sources,
                0,
                0
        );

        assertEquals(20, result.sources().size());
        assertTrue(result.sources().stream().allMatch(source ->
                source.startsWith("https://")));
        assertFalse(result.sourceSuffix().contains("javascript:"));
        assertFalse(result.sourceSuffix().contains("password"));
        assertTrue(result.finalContent().contains("https://example.com/valid"));
    }
}
