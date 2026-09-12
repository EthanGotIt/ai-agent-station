package cn.ethan.core.agent.thread;

/**
 * 类型职责：定义 Item payload 的结构化编码边界，隔离 Core 与具体 JSON 库。
 *
 * <p>业务层可以传递受控值或已有兼容文本；具体适配器负责生成版本化 envelope。
 * Core 不依赖 Jackson 或其他序列化实现。</p>
 *
 * @author ethan
 * @date 2026-09-13
 */
public interface AgentItemPayloadCodec {

    /** 将结构化业务值编码为指定类型的版本化 Item envelope。 */
    String encode(AgentItemTypeEnum type, Object data);

    /**
     * 将旧调用方产生的 JSON 或纯文本编码为指定类型的 envelope。
     * 新适配器应逐步使用 {@link #encode(AgentItemTypeEnum, Object)}。
     */
    String encodeJsonText(AgentItemTypeEnum type, String jsonOrText);
}
