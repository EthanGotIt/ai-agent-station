package cn.ethan.ai.domain.agent.model.valobj;

/**
 * 上下文预算估算器。借鉴 token count estimator 的抽象方式，但默认不绑定具体模型 tokenizer。
 */
public interface ContextUnitEstimator {

    int estimate(String text);

}
