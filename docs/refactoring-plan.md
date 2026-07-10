# AI Agent Station 重构计划

> 目标：弃用 LangGraph4j，转向 **Spring AI + spring-ai-community + Spring State Machine**；主链路升级为**轻量 Plan-and-Execute** 范式，Java 只守护金融副作用边界。
> 范围：基于当前 `codex/durable-after-sales-agent` 分支修改，不修改项目名称。
> 状态：本计划为新建文档，不覆盖旧文档。

---

## 一、当前问题诊断

### 1.1 DDD 主要问题

| 问题 | 说明 | 严重程度 |
|---|---|---|
| Domain 依赖 LangGraph4j | `domain/pom.xml` 依赖 `langgraph4j-core`；`AfterSalesAgentState extends AgentState` | 已修复 |
| 贫血 + Map 状态袋 | `AfterSalesAgentState` 基于 `Map<String, Object>`，缺乏业务行为 | 已用 SSM + Policy 封装 |
| 服务过大 | `AfterSalesAgentService` 混合校验、权限、审计、编排 | 已拆分为 Lifecycle / Authorization / Audit |
| 包结构不一致 | `adapter/port` 与 `adapter/repository` 并列；`service/exception` 位置不当 | 已修复 |
| 缺少共享内核 | 无 `types` 模块，ID 均为裸 `String` | 已新增 `ai-agent-station-types` |
| 无全局异常处理 | Controller 中大量 `try-catch` | 已新增 `@ControllerAdvice` |
| DTO 可变 | API DTO 使用 Lombok `@Data` | 已改为 Java `record` |
| 状态机过于繁琐 | 8 个 SSM 状态，模型能力未充分发挥 | **本次改造重点** |

### 1.2 技术栈替换方向

| 原组件 | 替换为 |
|---|---|
| LangGraph4j StateGraph | Spring State Machine（轻量 4 状态） |
| LangGraph4j MemorySaver | spring-ai-session `SessionMemoryAdvisor` |
| 本地 Tool Adapter | Spring AI `ChatClient` + Tool Calling |
| LangGraph4j checkpoint | 项目持久化 checkpoint + `after_sales_case` 已提交边界指针 |
| 自研工具编排 | spring-ai-agent-utils `TodoWriteTool` |

---

## 二、目标架构

```
ai-agent-station/
├── ai-agent-station-types/        # 共享内核：ID、异常、工具类
├── ai-agent-station-api/          # DTO（record）
├── ai-agent-station-app/          # Spring Boot 启动、装配
├── ai-agent-station-domain/       # Policy、端口、领域服务
├── ai-agent-station-infrastructure/# 仓库、适配器、状态机实现、PlanningAgent
└── ai-agent-station-trigger/      # HTTP、全局异常处理
```

### Domain 层结构

```
cn.ethan.ai.domain.agent/
├── policy/
│   ├── AfterSalesRefundEligibilityPolicy.java
│   ├── AfterSalesToolRecoveryPolicy.java
│   ├── AfterSalesToolErrorClassifier.java
│   ├── AfterSalesToolContractValidator.java
│   └── RefundInformationGatheringPolicy.java   # Plan 动作白名单
├── service/
│   ├── AfterSalesCaseLifecycleService.java
│   ├── AfterSalesAuthorizationService.java
│   └── AfterSalesAuditService.java
├── port/
│   ├── driven/
│   │   ├── IAfterSalesRepository.java
│   │   ├── IAgentRunRepository.java
│   │   ├── IOrderGateway.java
│   │   ├── IRefundGateway.java
│   │   ├── IAfterSalesEventPublisher.java
│   │   └── IAfterSalesStateMachine.java
│   └── driving/
│       └── IAfterSalesEventHandler.java
├── model/                               # 命令、结果、视图、Plan
│   └── plan/
│       ├── RefundPlan.java
│       ├── PlanStep.java
│       └── ChecklistItem.java
├── valobj/                              # 值对象与枚举
└── exception/
    └── AfterSalesResumeConflictException.java
```

### Infrastructure 层结构

```
cn.ethan.ai.infrastructure.adapter/
├── ai/
│   ├── SpringAiAfterSalesToolAdapter.java
│   ├── FaultInjectingAfterSalesToolAdapter.java
│   ├── RefundPlanningAgent.java          # 新增：ChatClient Plan
│   └── RefundInformationGatherer.java    # 新增：执行 Plan + RePlan
├── statemachine/
│   ├── SpringStateMachineAdapter.java    # 4 状态 SSM
│   └── ssm/
│       ├── AfterSalesState.java
│       └── AfterSalesEvent.java
├── repository/
├── commerce/
└── event/
```

---

## 三、主链路设计：轻量 Plan-and-Execute

### 3.1 状态机（4 个业务状态）

```text
INTAKE
  └─ Plan → Execute → RePlan（最多 3 次）
  └─ 信息完整且通过 Policy → PENDING_APPROVAL
PENDING_APPROVAL
  └─ APPROVE → 幂等退款 → COMPLETED
  └─ REJECT / 资格不符 → REJECTED
```

- `NEED_USER_INPUT` 不作为独立 SSM 状态，而是 `INTAKE` 阶段的内部标记。
- 模型只负责“规划还需要收集什么信息”，不决定退款。

### 3.2 Plan 输出格式（JSON）

```json
{
  "readyToEvaluate": false,
  "steps": [
    {"action": "ASK_USER", "targetField": "orderId", "reasonForUser": "请提供订单号"},
    {"action": "TOOL_CALL", "toolName": "query_order", "input": {"orderId": "ORDER-xxx"}}
  ],
  "checklist": [
    {"item": "确认订单号", "status": "PENDING"},
    {"item": "校验用户身份", "status": "PENDING"}
  ]
}
```

- `readyToEvaluate=true`：Java 直接跑 `AfterSalesRefundEligibilityPolicy`。
- `action` 白名单：`ASK_USER`、`TOOL_CALL`。
- 非法 action 由 `RefundInformationGatheringPolicy` 拦截。

### 3.3 RePlan 边界

- 触发条件：`query_order` 失败、订单不存在、用户信息变化、缺失字段补充后仍不完整。
- 最多 **3 次** RePlan。
- 超过 3 次或业务拒绝（跨用户、状态不可退）→ 直接进入 `REJECTED`。

### 3.4 Java 守护边界

| 能力 | 负责方 |
|---|---|
| 多轮对话、规划、记忆 | Spring AI `ChatClient` + `SessionMemoryAdvisor` |
| 订单查询工具 | `query_order` Tool Callback |
| 任务清单 | `TodoWriteTool`（spring-ai-agent-utils） |
| 资格判断 | `AfterSalesRefundEligibilityPolicy` |
| 审批身份校验 | `AfterSalesAuthorizationService` |
| 幂等退款执行 | `IAfterSalesRepository.executeRefund` |
| 并发恢复租约 | `AfterSalesCaseLifecycleService` + SSM checkpoint |

---

## 四、分阶段执行计划

### Phase 1：新增 types 模块（共享内核）

**状态：已完成**

- 新增 `ai-agent-station-types` 模块，包含强类型 ID 和异常基类。
- 根 `pom.xml` 加入 module 和 dependencyManagement。

### Phase 2：Domain 层解耦与服务拆分

**状态：已完成**

- 移除 `langgraph4j-core`。
- 拆分 `AfterSalesAgentService` 为 Lifecycle / Authorization / Audit。
- 重命名 Policy，调整包结构到 `port/driven` 与 `exception`。

### Phase 3：Infrastructure 层适配

**状态：已完成**

- 调整适配器包结构：`adapter/ai/`、`adapter/statemachine/`。
- 实现 `SpringStateMachineAdapter` 作为默认运行时。

### Phase 4：Trigger 与 API 层整理

**状态：已完成**

- DTO 改为 `record`。
- 新增全局异常处理。
- Controller 使用新的领域服务。

### Phase 5：接入 spring-ai-community

**状态：已完成**

- 添加 `spring-ai-session`、`spring-ai-agent-utils`。
- 配置 `SessionMemoryAdvisor` 与 `TodoWriteTool` bean。

### Phase 6：删除 LangGraph4j 残留

**状态：已完成**

- 删除 `LangGraph4jStateMachineAdapter`。
- 移除所有 `langgraph4j` 依赖与配置。
- 更新文档，表数量改为 9 张项目表。

### Phase 7：主链路升级为轻量 Plan-and-Execute

**状态：已完成**

#### 7.1 压缩状态机

**目标：** 把 SSM 从 8 状态压缩到 4 状态。

**关键动作：**
1. 简化 `AfterSalesStage`：保留 `INTAKE`, `PENDING_APPROVAL`, `COMPLETED`, `REJECTED`。
2. 重写 `SpringStateMachineAdapter`：
   - `INTAKE`：进入时调用 `RefundInformationGatherer`。
   - `PENDING_APPROVAL`：等待审批事件。
   - `COMPLETED/REJECTED`：终态。
3. 更新 `AfterSalesCaseLifecycleService.start/resume` 以匹配新状态。

**验收：** `mvn clean test` 15 个单元测试通过。

#### 7.2 引入 RefundPlanningAgent

**目标：** 让模型输出 JSON Plan，规划还需收集哪些信息。

**关键动作：**
1. 新增 `RefundPlan`, `PlanStep`, `ChecklistItem` records。
2. 新增 `RefundPlanningAgent`：
   - 使用 `ChatClient` + `SessionMemoryAdvisor`。
   - System Prompt 要求输出 JSON Plan。
   - `SessionMemoryAdvisor` 使用 `caseId` 隔离规划对话。
3. 新增 `RefundInformationGatheringPolicy`：
   - 校验 Plan action 是否在白名单。
   - 校验 Plan 是否收敛（不重复询问同一字段）。

**验收：** 新增单元测试覆盖 Plan 解析与白名单校验。

#### 7.3 引入 RefundInformationGatherer + RePlan

**目标：** 执行 Plan 步骤，支持最多 3 次 RePlan。

**关键动作：**
1. 新增 `RefundInformationGatherer`：
   - 执行 `ASK_USER` → 进入 `NEED_USER_INPUT` 内部标记。
   - 执行 `TOOL_CALL(query_order)` → 调用工具适配器。
   - 执行完成后判断是否需要 RePlan。
2. RePlan 逻辑：
   - 工具调用失败或信息仍不完整 → 调用 `RefundPlanningAgent` 重新规划。
   - 计数器达到 3 次 → 直接进入 `REJECTED`。
3. 信息完整后跑 `AfterSalesRefundEligibilityPolicy`，进入 `PENDING_APPROVAL`。

**验收：** `AfterSalesAgentServiceTest` 覆盖多轮澄清与 RePlan。

#### 7.4 接入 TodoWriteTool

**目标：** 信息收集完成后生成退款检查清单。

**关键动作：**
1. 在 `RefundPlanningAgent` 或 `RefundInformationGatherer` 中调用 `TodoWriteTool`。
2. 生成 checklist 后作为业务状态的一部分写入 `AfterSalesAgentState`。
3. 审批时 checklist 随 Case 返回给审批人。

**验收：** 新增单测验证 checklist 生成。

#### 7.5 测试与文档

**关键动作：**
1. 更新 `AfterSalesGraphTest`、`AfterSalesTrajectoryEvaluationTest` 期望状态。
2. 更新 `AfterSalesLiveModelEvaluationIT`，产出真实模型评估报告。
3. 更新 `AfterSalesConcurrencyBenchmarkIT`，新架构并发基准。
4. 更新 README 与 docs，明确当前为 Plan-and-Execute 范式。

**验收：**
- `mvn clean test` 通过。
- `AfterSalesLiveModelEvaluationIT` 30/30 Tool 合同通过。
- `AfterSalesConcurrencyBenchmarkIT` 吞吐 >= 400 tasks/s，P95 < 100 ms。

---

## 五、原则

1. **DDD 标准**：Domain 层只放 Policy、端口、领域服务；Infrastructure 放实现。
2. **不过度扩展**：不为未来可能的功能抽象接口，当前需要多少就定义多少。
3. **每阶段清冗余**：每个阶段完成后，删除无用代码、合并重复 helper、更新文档。
4. **模型不碰副作用**：退款、审批、跨用户访问等关键操作必须由 Java 控制。

---

## 六、风险与应对

| 风险 | 应对 |
|---|---|
| Plan 输出不稳定 | 用 JSON schema + 后校验；失败时 fallback 到 `ASK_USER` |
| RePlan 循环不收敛 | 最多 3 次；超过由 Java Policy 终止 |
| 模型绕过白名单 | `RefundInformationGatheringPolicy` 硬拦截；非白名单 action 直接拒绝 |
| 改造成本大 | 分 4 个小阶段，每阶段独立验收 |
| spring-ai-community API 变化 | 用端口隔离，底层可替换 |

---

*计划更新时间：2026-07-06*
