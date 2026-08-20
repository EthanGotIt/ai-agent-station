import { useState, type FormEvent, type KeyboardEvent } from "react";
import { Archive, Bot, CheckCircle2, CircleAlert, GitBranch, ListPlus, PackageSearch, Plus, Send, Square, TerminalSquare, Truck, X } from "lucide-react";
import type { QuestionCardState } from "./threadTypes";
import type { useThreadWorkspace } from "./useThreadWorkspace";

type Props = { workspace: ReturnType<typeof useThreadWorkspace>; userId: string };

function statusLabel(status: string) {
  return ({ QUEUED: "排队中", ACTIVE: "执行中", WAITING_USER_INPUT: "等待确认", WAITING_EXTERNAL_ACTION: "等待外部动作", COMPLETED: "已完成", CANCELLED: "已取消", TIMED_OUT: "已超时", FAILED: "失败", MANUAL_RETRY_REQUIRED: "需要人工重试" } as Record<string, string>)[status] ?? status;
}

function time(value: string) { return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" }).format(new Date(value)); }

function eventPayloadText(payload: string) {
  try {
    const parsed = JSON.parse(payload) as { data?: unknown };
    if (parsed && typeof parsed === "object" && "data" in parsed) {
      return typeof parsed.data === "string" ? parsed.data : JSON.stringify(parsed.data);
    }
    return JSON.stringify(parsed);
  } catch {
    return payload;
  }
}

function QuestionCard({ value, disabled, onSubmit }: { value: QuestionCardState; disabled: boolean; onSubmit: (answers: Record<string, string>) => void }) {
  const [decision, setDecision] = useState("APPROVE");
  return <form className="decision-card question-card" onSubmit={(event) => { event.preventDefault(); onSubmit({ decision }); }}>
    <div className="decision-heading"><CheckCircle2 aria-hidden="true" /><div><h2>{value.title}</h2><p>持久化检查点 · 版本 {value.version}</p></div></div>
    <p>{value.prompt}</p>
    <label>决定<select value={decision} disabled={disabled} onChange={(event) => setDecision(event.target.value)}><option value="APPROVE">确认执行</option><option value="REJECT">拒绝执行</option></select></label>
    <div className="actions"><button type="submit" disabled={disabled}><CheckCircle2 aria-hidden="true" />提交决定</button><button className="secondary" type="button" disabled={disabled} onClick={() => onSubmit({ decision: "REJECT" })}><X aria-hidden="true" />取消动作</button></div>
  </form>;
}

function Turn({ turn, busy, retryingRunId, onRetry }: {
  turn: ReturnType<typeof useThreadWorkspace>["turns"][number];
  busy: boolean;
  retryingRunId: string | null;
  onRetry: (runId: string) => void;
}) {
  const retryable = turn.externalActionStatus === "MANUAL_RETRY_REQUIRED" && turn.workflowRunId;
  return <article className={`conversation-turn thread-turn turn-${turn.status.toLowerCase()}`}>
    <div className="turn-request"><span className="turn-avatar user-avatar">你</span><div><div className="turn-meta"><strong>Turn 输入</strong><time>{time(turn.startedAt)}</time><span className={`turn-status status-${turn.status.toLowerCase()}`}>{statusLabel(turn.status)}</span></div><p>{turn.userMessage}</p></div></div>
    <div className="turn-response"><span className="turn-avatar agent-avatar"><Bot aria-label="Agent" /></span><div><div className="turn-meta"><strong>Agent</strong><span className="turn-route">Thread Runtime</span></div>{turn.content ? <p className="agent-content">{turn.content}</p> : turn.status === "ACTIVE" || turn.status === "QUEUED" ? <p className="agent-content loading-copy">正在构造上下文并执行工具…</p> : null}{turn.error ? <p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{turn.error}</p> : null}{retryable ? <div className="actions turn-actions"><button className="secondary" type="button" disabled={busy || retryingRunId === turn.workflowRunId} onClick={() => onRetry(turn.workflowRunId as string)}>{retryingRunId === turn.workflowRunId ? "重试已排队" : "人工重试"}</button></div> : null}</div></div>
  </article>;
}

export function ThreadWorkspace({ workspace, userId }: Props) {
  const [message, setMessage] = useState("");
  const [title, setTitle] = useState("");
  const [traceOpen, setTraceOpen] = useState(true);
  const currentThread = workspace.threads.find((item) => item.threadId === workspace.threadId);
  const submit = (event: FormEvent) => { event.preventDefault(); const next = message.trim(); if (!next) return; void workspace.send(next); setMessage(""); };
  const keyboard = (event: KeyboardEvent<HTMLTextAreaElement>) => { if ((event.ctrlKey || event.metaKey) && event.key === "Enter") { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } };
  return <section className="thread-workspace" aria-labelledby="thread-workspace-heading">
    <div className="workspace-heading"><div><p className="eyebrow">COMMERCE GUARDIAN AGENT</p><h1 id="thread-workspace-heading">一个 Thread，完整记录一次 Agent 工作</h1><p>消息、工具、Workflow 和用户确认都落为可恢复 Item。业务上下文只是可选的辅助信息。</p></div><button className="icon-button" type="button" onClick={() => void workspace.createThread()}><Plus aria-hidden="true" />新建 Thread</button></div>
    <div className="thread-layout">
      <aside className="thread-sidebar" aria-label="Thread 列表"><div className="sidebar-heading"><div><span className="eyebrow">THREADS</span><h2>工作上下文</h2></div><button className="secondary compact-icon" type="button" onClick={() => void workspace.createThread()} aria-label="新建 Thread"><ListPlus aria-hidden="true" /></button></div><div className="thread-list">{workspace.threads.map((item) => <button type="button" key={item.threadId} className={`thread-row ${item.threadId === workspace.threadId ? "selected" : ""}`} onClick={() => void workspace.selectThread(item.threadId)}><span><strong>{item.title}</strong><small>{item.contextId ?? "无业务上下文"}</small></span><span className={`status status-${item.status.toLowerCase()}`}>{item.status === "ACTIVE" ? "进行中" : "已归档"}</span></button>)}</div><div className="thread-sidebar-note"><GitBranch aria-hidden="true" /><p>暂不引入 Thread Fork；先把单 Thread 的恢复、预算和轨迹做深。</p></div></aside>
      <div className="thread-main"><div className="thread-context-bar"><div><span className="eyebrow">CURRENT THREAD</span><strong>{currentThread?.title ?? "加载中…"}</strong></div><div className="thread-context-actions"><label className="rename-field"><span className="sr-only">Thread 标题</span><input value={title} placeholder="重命名…" onChange={(event) => setTitle(event.target.value)} onBlur={() => { if (title.trim()) { void workspace.rename(title.trim()); setTitle(""); } }} /></label><span className="status status-active">{userId}</span></div></div><div className="quick-start-strip"><span className="eyebrow">QUICK START</span><button type="button" disabled={workspace.busy} onClick={() => void workspace.send("查询订单 ORDER-PAID-001 的当前状态") }><PackageSearch aria-hidden="true" />查询订单</button><button type="button" disabled={workspace.busy} onClick={() => void workspace.send("查询订单 ORDER-SHIPPED-STALLED-001 的物流状态") }><Truck aria-hidden="true" />查询物流</button></div><div className="thread-records">{workspace.loading ? <div className="conversation-empty"><Bot aria-hidden="true" /><h2>正在恢复 Thread</h2><p>从 Items 和上下文快照读取历史。</p></div> : workspace.turns.length === 0 ? <div className="conversation-empty"><TerminalSquare aria-hidden="true" /><h2>从一次清晰的请求开始</h2><p>你可以查询订单或物流，也可以请求退款、催发货，观察 Agent 如何把高风险写入转为 Workflow 确认。</p></div> : workspace.turns.map((turn) => <Turn key={turn.turnId} turn={turn} busy={workspace.busy} retryingRunId={workspace.retryingRunId} onRetry={workspace.retry} />)}{workspace.question ? <QuestionCard key={`${workspace.question.runId}:${workspace.question.questionId}`} value={workspace.question} disabled={workspace.busy} onSubmit={(answers) => void workspace.answer(answers)} /> : null}</div><form className="composer" onSubmit={submit}><label htmlFor="thread-message">输入请求</label><textarea id="thread-message" value={message} disabled={workspace.busy || !workspace.threadId} onKeyDown={keyboard} onChange={(event) => setMessage(event.target.value)} placeholder="例如：查询订单 ORDER-SHIPPED-STALLED-001 的物流状态，或发起退款…" /><div className="composer-actions"><span>Ctrl / ⌘ + Enter 发送</span>{workspace.busy ? <button className="secondary icon-button" type="button" onClick={() => void workspace.cancel()}><Square aria-hidden="true" />取消当前 Turn</button> : <button className="icon-button" type="submit" disabled={!message.trim() || !workspace.threadId}><Send aria-hidden="true" />提交 Turn</button>}</div></form></div>
      <aside className={`execution-inspector trace-inspector${traceOpen ? "" : " is-collapsed"}`}><div className="inspector-heading"><div><span className="eyebrow">EXECUTION LEDGER</span><h2>运行轨迹</h2><p>Item 是事实，SSE 是实时投影。</p></div><button className="secondary compact-icon" type="button" onClick={() => setTraceOpen((open) => !open)} aria-expanded={traceOpen} aria-label={traceOpen ? "折叠运行轨迹" : "展开运行轨迹"}><Archive aria-hidden="true" /></button></div>{traceOpen ? <>{workspace.error ? <p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{workspace.error}</p> : null}<ol className="trace-list">{workspace.trace.length === 0 ? <li className="trace-empty"><TerminalSquare aria-hidden="true" /><p>提交 Turn 后，这里会出现队列、工具、Workflow 和终态事件。</p></li> : workspace.trace.slice(-32).map((event) => <li className="trace-event" key={`${event.eventId}-${event.timestamp}`}><span className="trace-icon"><CheckCircle2 aria-hidden="true" /></span><div><div className="trace-meta"><strong>{event.type}</strong><time>{time(event.timestamp)}</time></div><p>{eventPayloadText(event.payload)}</p></div></li>)}</ol></> : null}</aside>
    </div>
  </section>;
}
