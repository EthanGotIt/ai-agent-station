status: completed
updated: 2026-08-20

# Commerce Guardian Agent 交接

## 已完成

- 已建立 Agent-first 分包守则，完成 Core、Infrastructure 和 App 的能力内聚迁移。
- 已拆分 Thread、Turn、Item、QuestionCard、ContextSnapshot 存储端口，并保持 MyBatis-Plus 适配器可构建。
- 已将 SSE 事件总线移到 App `agent.stream`，Core 仅保留事件发布和订阅端口。
- 已移除无调用的订单分析、商品明细和近期订单分支，以及对应的直接依赖和冗余导入。
- Item 序号从 1 开始，`afterSequence=0` 可完整恢复首条事实；已增加上下文预算、快照和 Thread FIFO 测试。
- 文档、检查器、测试包路径和前端元数据已对齐 Commerce Guardian Agent / `agent-console` 命名。

## 最近验证

- `python -m scripts.convention_check`：通过。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：2 个测试通过。
- `mvn clean '-DskipTests=false' test`：Core 2、Infrastructure 5、App 4，共 11 个测试通过。
- `agent-console/npm run typecheck`、`npm test -- --run`、`npm run test:e2e`、`npm run build`：通过。

## 未执行

`python -m scripts.acceptance` 需要已启动的 Agent API、MySQL 和模型服务；本次本地环境未启动服务，命令因连接被拒绝退出，未将其误报为通过。

用户已有的前端目录/SQL 基线重命名、Hook、部署和 IDE 改动保持在工作区，未混入本次阶段提交。
