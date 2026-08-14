# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

项目作者本人；在本地梳理、验证和展示 AI Agent 的业务闭环时使用。校招面试官是次要观看者，应能快速理解系统的工程能力与真实业务边界。

## Product Purpose

AI Agent Station 是一个电商履约与售后智能助手。它将订单、物流和退款任务编排为确定性 Workflow，并将开放式只读分析与低风险会话偏好写入交给 ReAct。

## Positioning

同一控制台可直观看到 Agent 路由、SSE 执行过程、持久化 QuestionCard、工具确认、结构化业务结果和退款可靠性闭环；关键写入始终受确定性 Workflow 约束。

## Operating Context

本地 Web 控制台用于个人探索与校招工程展示。演示使用仓库初始化的 `demo-user-1`、订单、物流和售后数据；控制台通过 Vite 代理访问本机后端，并只在页面内存中保存会话上下文。

## Capabilities and Constraints

- 提供 Agent 聊天、SSE、订单/物流/售后业务卡片、Workflow QuestionCard、ASK 工具确认、取消、会话记忆和售后审核。
- 控制台可使用 `ORDER-PAID-001` 与 `ORDER-SHIPPED-STALLED-001` 等真实演示数据启动场景。
- 不修改后端 API、HTTP DTO、数据库结构或业务状态机；不虚构生产认证、真实支付渠道、性能指标或业务数据。
- 项目用于校招工程能力展示，不是生产运营后台，也不要求上线或现场演示。

## Evidence on Hand

现有可运行的 React 控制台、后端测试、V2 与 V2.1 验收文档，以及本机 MySQL 退款验收报告。没有可作为产品事实使用的真实客户、商业指标或品牌资产。

## Product Principles

- 先展示一次完整、可理解的业务闭环，再呈现实现细节。
- 真实运行状态优先于装饰性指标或虚构数据。
- 高风险业务写入保持显式确认与可追溯边界。
- 工程细节按需展开，不能压倒当前任务。
- 控制台应美观、稳定、适合长期个人使用。

## Accessibility & Inclusion

中文为界面主语言；保留键盘操作、可见焦点、200% 缩放、系统明暗主题、`prefers-reduced-motion` 和强制高对比度支持。
