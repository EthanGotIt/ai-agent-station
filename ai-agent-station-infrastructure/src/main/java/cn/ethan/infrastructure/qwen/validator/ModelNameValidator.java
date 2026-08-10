package cn.ethan.infrastructure.qwen.validator;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 模型名称校验器：在应用接收请求前限制各职责可使用的 Qwen 模型。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Component
public final class ModelNameValidator {

    private static final String SNAPSHOT_SUFFIX = "(?:\\d{8}|\\d{4}-\\d{2}-\\d{2})";
    private static final Pattern ROUTER_MODEL = Pattern.compile(
            "qwen3\\.7-plus(?:-" + SNAPSHOT_SUFFIX + ")?"
    );
    private static final Pattern REACT_MODEL = Pattern.compile(
            "qwen3\\.7-plus(?:-" + SNAPSHOT_SUFFIX + ")?|"
                    + "qwen3\\.8-max(?:-(?:preview|" + SNAPSHOT_SUFFIX + "))?"
    );

    private final String router;
    private final String react;

    public ModelNameValidator(
            @Value(
                    "${spring.ai.openai.chat.model:"
                            + "${ai-agent.model.router:qwen3.7-plus}}"
            ) String router,
            @Value("${ai-agent.model.react:qwen3.7-plus}") String react
    ) {
        this.router = router;
        this.react = react;
    }

    @PostConstruct
    void validate() {
        validateRole("router", router, ROUTER_MODEL, "qwen3.7-plus 或其日期快照");
        validateRole("react", react, REACT_MODEL,
                "qwen3.7-plus 或经过对照验收的 qwen3.8-max");
    }

    private void validateRole(String role, String model, Pattern allowed, String expected) {
        if (model == null || !allowed.matcher(model).matches()) {
            throw new IllegalStateException(
                    role + " model must use " + expected + ": " + model
            );
        }
    }
}
