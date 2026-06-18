package cn.ethan.ai.domain.agent.model.valobj;

/**
 * Armory 单次装配上下文，承载客户端、模型、提示词和工具等装配阶段对象。
 * 继承扳手树路由上下文仅用于满足框架泛型约束，业务代码统一使用本类表达装配语义。
 */
public class ArmoryAssemblyContextVO extends cn.ethan.wrench.design.framework.tree.DynamicContext {
}
