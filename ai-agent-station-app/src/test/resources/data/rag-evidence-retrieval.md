# 证据治理 Agentic RAG 与 Session 记忆

## 证据检索链路

AI Agent Station 的 RAG 能力收敛到 Spring AI 2 Advisor Chain。`EvidenceRetrievalAdvisor` 负责根据当前客户端允许的知识库范围检索项目知识，Observation Trace 层负责汇总 RAG metadata 和工具 evidence，生成可追踪的 `rag_evidence` 事件。

项目知识统一使用 PGVector 语义召回，并强制携带当前客户端允许的 `ragId` 过滤。召回结果统一补充来源、查询和排名元数据，再由单次调用内的 EvidenceAccumulator 按来源与内容去重。

BM25/RRF 与 Small-to-Big 在冻结评测中未达到保留门槛，已从生产检索链路移除。固定 Advanced RAG 只作为测试代码中的历史对照，不与当前 Runtime 并存。

## 引用与拒答

每条规范化证据都有来源类型、标题、URI 或项目文档标识、正文和检索时间。最终事实回答应优先基于已检索证据组织，不把模型猜测或伪造的工具输出当作证据。

当前链路保留证据不足拒答和 evidence trace，不把普通网页 AI 式的无来源回答包装成可信结论。引用校验和纠错只按已落地能力表述，不夸大为完整 Self-RAG 或行业标准 Agentic RAG 3.0。

## Session 短期记忆

系统只把成功的 USER 与 ASSISTANT 组成完整 Turn 注入后续请求，失败或取消 Run 的孤立 USER 不进入上下文。上下文由滚动结构化摘要和最近四个完整 Turn 组成，不按单条消息截断。

结构化摘要只保存目标、约束、已确认决策、未解决问题和回答偏好，不保存工具输出、外部事实或模型猜测。Session 使用乐观锁避免并发覆盖，成功交互续期三十天，并由定时任务清理过期消息和摘要。

当前记忆定位是可跨请求、跨重启恢复的 Session 短期记忆，不是长期用户画像，也不是向量化长期记忆。
