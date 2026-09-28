# P7：隔离交付验收与回滚演练

## 前置条件

P0—P6 必需项通过。验收范围是可复现的隔离交付，不含真实生产接入。

## 执行

1. 从干净环境按 Runbook 启动服务，以 MySQL 和订单夹具完整演练退款、催发货、删除及拒绝、事实变化、重试、未知核验和人工处理。
2. 覆盖同 Thread FIFO、并发批准、多 Worker、重启、601 条 Items 分页、游标收口、刷新、SSE 断线恢复。
3. 浏览器复核桌面、窄屏、移动端的交互、错误焦点、事项归属、业务状态和离线重连。
4. 备份隔离数据库，兼容版本升级至最终版本，再回滚到支持当前 Schema/Java Run/新状态的兼容版本。
5. 回滚后验证 Run、Checkpoint、Command、Items 可恢复且无重复业务写入；禁止用破坏性基线 SQL 重建既有库。
6. 执行完整 Python、Maven、MySQL acceptance、前端矩阵，记录 revision、工具版本、时间和证据类别。
7. 更新架构现状/目标、Runbook、实施追踪和 handoff；生产鉴权、真实订单系统另列部署条件。

## 验收、回滚、交接

其他工程师按文档可复现全部隔离流程；自动化、模型、浏览器、回滚证据分别标明合成/真实。回滚后幂等身份和事实完整。所有必需项完成才将总体计划与 handoff 标记 completed。

## 本次执行证据（2026-09-28）

- 从提交 `df58ce4` 的干净快照启动最终 Jar；为修复隔离启动时 Spring 无法代理 `@Repository` 的阻塞，`MybatisOrderWriteReservationStore` 去除 `final`，该代码修复需随 P7 提交。
- 临时 MySQL schema `CGA_P7_DD3737D06C15407B` 已按现有基线标记为 v14；应用 Flyway 成功校验 15 个迁移并在 8090 启动。独立订单夹具运行于 18080，独立 SQLite 使用一次性 `.p7-runtime` 文件，两个健康检查均返回 UP。
- Python HTTP acceptance（用户 `p7-user2`、催发货注入 3 次临时失败、允许一次性删除）通过：Thread 列表/创建、Item 恢复、开放交互唯一性、刷新恢复、Turn 幂等、执行轨迹回放、物流、退款幂等、催发货重试、删除幂等。
- 干净快照 Maven `clean test` 通过：Core 98、Infrastructure 109、App 22；`context-acceptance,workflow-acceptance verify` 通过：HTTP 集成 9、MySQL acceptance 13。MySQL 测试创建临时 schema 并执行 V9→V14 迁移后清理。
- 前端既有门禁通过：typecheck、Vitest 77、build。真实浏览器默认桌面尺寸在最终服务启动后能加载对话列表和输入区；服务未启动时也复核了错误提示与重连按钮。

以下仍不满足总体完成门槛：P6 两套真实模型的 54 条 Live 评测、兼容版本→最终版本→兼容版本的部署回滚演练、601 条 Items/异常游标与 SSE 断线专项、窄屏/移动尺寸浏览器专项，以及目标环境旧 Run/continuation 盘点。P7 保持 active，不能据此宣布整体验收完成。
