# Java 21 与 Redis 升级评估

- 状态：已决策
- 日期：2026-07-12
- 结论：保持 Java 17，不引入 Redis

## 背景

项目当前以 Java 17 编译，使用 Spring Boot 4.1.0、Spring AI 2.0.0、Spring State Machine、MyBatis、MySQL 和 `spring-ai-session` JDBC。评估目标是确认 Java 21 或 Spring AI 2 的 Redis Memory、Vector Store、Semantic Cache 是否能为当前受控售后 Agent 提供可验证收益。

Spring Boot 4.1.0 的官方最低要求仍是 Java 17，并明确兼容 Java 21，因此当前构建基线有效，无框架强制升级压力。[Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)

## Java 21 验证

同一提交分别在 Corretto 17.0.14 和 Temurin 21.0.11 上运行，POM 始终保持 `--release 17`：

| 验证项 | Java 17 | Java 21 |
|---|---:|---:|
| 离线测试 | 45/45，通过 | 45/45，通过 |
| MySQL Testcontainers | 1/1，通过，0 skipped | 1/1，通过，0 skipped |
| 并发基准错误 | 0 | 0 |
| 吞吐 | 1290.32 tasks/s | 1333.33 tasks/s |
| P50 | 21 ms | 20 ms |
| P95 | 27 ms | 24 ms |
| P99 | 30 ms | 27 ms |
| 最大延迟 | 35 ms | 27 ms |

该基准只有 200 个内存任务和模拟阻塞，不代表真实 HTTP、MySQL 或模型链路容量。Java 21 在单次运行中吞吐约提升 3.3%，不足以单独支撑最低基线升级；当前仓库也没有 CI 或生产镜像配置可以证明所有环境已统一到 Java 21。

虚拟线程不与 JDK 升级绑定实施。当前状态机、MySQL 事务、连接池、恢复锁和退款幂等链路需要独立的线程上下文与容量验证，不能根据内存基准直接开启。

### Java 21 后续触发条件

满足以下任一条件后重新评估：

1. 关键框架或依赖正式停止支持 Java 17。
2. CI、开发机和部署镜像均已具备 Java 21，且回滚镜像可用。
3. 真实 HTTP、MySQL 和模型负载测试证明 Java 21 或虚拟线程有稳定收益。

若升级，必须独立修改 POM、Maven Enforcer、CI、镜像、脚本和基准文档；不得与 Redis 改造合并。回滚方式是恢复 Java 17 编译目标和运行镜像，数据库与 HTTP 契约无需迁移。

## Redis 验证

本机现有 Redis 为 5.0.14.1。Spring AI 2 的 `RedisChatMemoryRepository` 依赖 Redis Stack 的 RedisJSON 与 Query Engine；Redis Vector Store 和 Semantic Cache 同样要求 Redis Stack，并在向量检索场景中需要 Embedding Model。[Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) [Spring AI Redis Vector Store](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)

因此现有 Redis 不能直接承载这些能力，引入它们意味着新增 Redis Stack 服务、索引、容量、备份、监控和故障降级，而不是增加一个普通 starter。

当前项目没有支持该成本的需求证据：

- Case 级规划记忆已由 `spring-ai-session` JDBC 持久化，未发现延迟或数据库竞争瓶颈。
- Case、Turn、checkpoint、审批、退款和 Outbox 必须继续以 MySQL 为事实来源。
- 当前不建设通用 RAG，没有文档向量检索需求。
- 规划结果依赖用户、Case、证据和 RePlan 上下文，语义缓存存在跨上下文复用错误计划的风险。

### Redis 后续触发条件

只有出现以下明确需求才分别立项：

1. JDBC Session 出现可复现的性能瓶颈，或多实例低延迟共享成为生产要求：评估 Redis Chat Memory。
2. 正式批准知识检索范围、数据集和召回指标：评估 Redis Vector Store。
3. 真实模型调用中存在足够重复请求，并能证明隔离键、TTL 和命中正确率：评估 Semantic Cache。

任何 Redis 方案都必须保证 Redis 不可用时确定性 Plan 仍可运行，并且不得接管 checkpoint、业务状态、退款幂等或 Outbox。回滚方式是关闭对应 Advisor/Repository 配置并恢复 JDBC Session；业务数据无需迁移。

## 决策

当前采用方案 1：保持 Java 17，不引入 Redis。Java 21 已证明兼容，可作为后续独立升级候选；Redis Memory、Vector Store 和 Semantic Cache 均等待需求与指标触发。
