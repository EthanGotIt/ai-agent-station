# P5：排空旧 Run 并退出 LangGraph4j

## 执行记录（2026-09-28）

- 已加入只读盘点脚本 [`scripts/maintenance/workflow-inventory.sql`](../../scripts/maintenance/workflow-inventory.sql)，覆盖旧编排非终态 Run、开放交互、未结算命令、待消费 continuation 和历史图快照；脚本没有写入、DDL 或锁表语句，并由 `test_workflow_inventory.py` 校验。
- 新 Run 路由默认只接受 Java Workflow；历史 `LEGACY_V1`/`EXPEDITE_GRAPH_V1`/`EXPEDITE_GRAPH_V2` 在恢复时进入 `RetiredAgentWorkflowEngine`，以 `WORKFLOW_COMPATIBILITY_REQUIRED` 明确拒绝错误恢复，不按新语义静默执行。
- LangGraph4j 依赖、图引擎、图节点、技术快照 Entity/Mapper 和生产 continuation gateway 已从最终提交索引移除。历史数据库迁移、快照表、Turn continuation 字段、Item 类型和版本枚举保留，继续支持历史读取与迁移验收。
- `application.yml` 不再提供自动 continuation 或旧图模式准入；Worker 结果投影不创建 continuation。提交前使用干净快照验证，工作区中已有的 V2/评测文件不纳入本阶段提交。

真实目标库的零状态盘点需要只读 MySQL 凭据；当前环境未提供该凭据，因此本阶段不虚报线上旧 Run/continuation 已归零。部署前必须执行盘点脚本并保存结果，确认旧 Worker/服务实例已退出后才能关闭兼容准入。

## 前置条件

新建三类写操作均使用 Java Workflow，兼容版本可恢复旧 Run。

## 执行

1. 只读盘点旧 Run、Checkpoint、开放交互、非终态命令、待核验动作和待消费 continuation。
2. 旧事项经原兼容路径完成；等待用户的事项由用户完成或明确取消。未知副作用先核验，不强制归入终态。
3. 以上非终态记录全部清零，旧服务/Worker/队列退出后再关闭旧准入。
4. 移除 LangGraph4j 依赖、图引擎/节点、技术快照读写、配置和自动 continuation 创建/调度。
5. 保留历史版本标识、快照数据及旧 Item/Turn 的只读解析；遗留非终态 Run 必须明确报错并指向兼容版本。
6. 记录兼容版本、数据库备份、排空报告和回滚步骤；不删除业务历史。

## 验收、回滚、交接

代码和隔离验收要求：依赖树与运行路径不含 LangGraph4j 和自动续跑，历史事实仍可读，遗漏旧 Run 受控拒绝；目标环境还需用只读盘点报告证明旧非终态 Run、开放交互、未结算命令和待消费 continuation 为零。回滚只到支持当前 Schema、新状态和 Java Run 的兼容版本。完成目标库排空后转 P6/P7。
