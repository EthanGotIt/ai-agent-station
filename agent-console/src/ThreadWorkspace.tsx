import { Fragment, useEffect, useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import {
  Archive,
  ArchiveRestore,
  Bot,
  Check,
  CheckCircle2,
  CircleAlert,
  Clock3,
  GitBranch,
  ListPlus,
  Menu,
  PanelRight,
  Pencil,
  RotateCcw,
  Search,
  Send,
  ShieldCheck,
  Square,
  Truck,
  Undo2,
  X
} from "lucide-react";
import type {
  AgentItem,
  BusinessProgressStatus,
  LogisticsTimeline,
  OrderActionType,
  OrderCard,
  QuestionCardState,
  QuestionField,
  ThreadStatus,
  ThreadViewTurn
} from "./threadTypes";
import type { useThreadWorkspace } from "./useThreadWorkspace";

type Props = { workspace: ReturnType<typeof useThreadWorkspace>; userId: string };

function statusLabel(status: string) {
  return ({
    QUEUED: "排队中", ACTIVE: "处理中", WAITING_USER_INPUT: "等待确认", WAITING_EXTERNAL_ACTION: "外部处理中",
    COMPLETED: "已完成", CANCELLED: "已取消", TIMED_OUT: "已超时", FAILED: "失败", MANUAL_RETRY_REQUIRED: "需要人工重试"
  } as Record<string, string>)[status] ?? status;
}

function time(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? "—" : new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" }).format(parsed);
}

function dateTime(value: string | null) {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? "—" : new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(parsed);
}

function amount(order: OrderCard) {
  if (order.paidAmount === null) return "金额未知";
  return `${order.currency ?? "¥"} ${order.paidAmount.toFixed(2)}`;
}

function orderStatus(status: string) {
  return ({ PAID: "已支付", SHIPPED: "运输中", DELIVERED: "已送达", CANCELLED: "已取消", REFUNDED: "已退款" } as Record<string, string>)[status] ?? status;
}

function inlineMarkdown(value: string, keyPrefix: string): ReactNode[] {
  return value.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, index) => {
    if (part.startsWith("**") && part.endsWith("**")) return <strong key={`${keyPrefix}-strong-${index}`}>{part.slice(2, -2)}</strong>;
    if (part.startsWith("`") && part.endsWith("`")) return <code key={`${keyPrefix}-code-${index}`}>{part.slice(1, -1)}</code>;
    return <Fragment key={`${keyPrefix}-text-${index}`}>{part}</Fragment>;
  });
}

/** 只渲染受控粗体、行内代码和换行；订单事实统一由结构化卡片表达。 */
function RestrictedMarkdown({ value, className }: { value: string; className: string }) {
  return <div className={className}>{value.split(/\n{2,}/).filter((block) => block.trim()).map((block, blockIndex) => <p key={`block-${blockIndex}`}>
    {block.split("\n").map((line, lineIndex) => <Fragment key={`line-${blockIndex}-${lineIndex}`}>{lineIndex > 0 ? <br /> : null}{inlineMarkdown(line, `line-${blockIndex}-${lineIndex}`)}</Fragment>)}
  </p>)}</div>;
}

function LogisticsTimelineView({ timeline }: { timeline: LogisticsTimeline }) {
  if (timeline.events.length === 0) return <p className="timeline-empty">暂时没有可展示的物流节点。</p>;
  return <ol className="logistics-timeline" aria-label={`${timeline.orderId} 物流时间线`}>{timeline.events.map((event) => <li key={event.eventId}>
    <span className="timeline-dot" /><div><div><strong>{event.status}</strong><time>{dateTime(event.occurredAt)}</time></div><span>{event.location || "物流节点"}</span><p>{event.description}</p></div>
  </li>)}</ol>;
}

function OrderResults({ turn, disabled, onAction }: { turn: ThreadViewTurn; disabled: boolean; onAction: (sourceTurnId: string, orderId: string, actionType: OrderActionType) => void }) {
  const { orderCards: orders, logisticsTimelines: timelines } = turn;
  if (orders.length === 0 && timelines.length === 0) return null;
  const timelineByOrder = new Map(timelines.map((timeline) => [timeline.orderId, timeline]));
  const visibleOrderIds = new Set(orders.map((order) => order.orderId));
  const orphanTimelines = timelines.filter((timeline) => !visibleOrderIds.has(timeline.orderId));
  return <section className="turn-facts" aria-label={`${turn.turnId} 订单事实`}>
    <div className="result-heading"><div><span className="eyebrow">STRUCTURED FACTS</span><h3>{orders.length > 0 ? `找到 ${orders.length} 个匹配订单` : "物流时间线"}</h3></div><span className="sequence-caption">来自本 Turn</span></div>
    {orders.length > 0 ? <div className="order-card-grid">{orders.map((order) => {
      const timeline = timelineByOrder.get(order.orderId);
      return <article className="order-card" key={order.orderId}>
        <div className="order-card-heading"><div><strong>{order.itemSummary ?? "订单商品"}</strong><span>{order.orderId}</span></div><span className={`status status-${order.status.toLowerCase()}`}>{orderStatus(order.status)}</span></div>
        <div className="order-card-meta"><span>{amount(order)}</span><span>下单 {dateTime(order.createdAt)}</span><span>{order.visibility === "HIDDEN" ? "已隐藏" : order.logisticsStatus ?? "暂无物流状态"}</span></div>
        {timeline ? <LogisticsTimelineView timeline={timeline} /> : null}
        <div className="order-card-actions" aria-label={`${order.orderId} 可用操作`}>
          <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "QUERY_LOGISTICS")}><Truck aria-hidden="true" />查物流</button>
          {order.status !== "REFUNDED" && order.status !== "CANCELLED" ? <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "REFUND")}><Undo2 aria-hidden="true" />申请退款</button> : null}
          {order.status === "PAID" ? <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "EXPEDITE")}><PackageSearchIcon />催发货</button> : null}
          {order.visibility === "HIDDEN" ? <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "RESTORE_ORDER")}><ArchiveRestore aria-hidden="true" />恢复记录</button> : <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "HIDE_ORDER")}><Archive aria-hidden="true" />隐藏记录</button>}
        </div>
      </article>;
    })}</div> : null}
    {orphanTimelines.map((timeline) => <article className="order-card" key={timeline.orderId}><div className="order-card-heading"><div><strong>物流时间线</strong><span>{timeline.orderId}</span></div></div><LogisticsTimelineView timeline={timeline} /></article>)}
  </section>;
}

function PackageSearchIcon() {
  return <svg aria-hidden="true" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2"><path d="m21 8-9-5-9 5 9 5 9-5Z" /><path d="M3 8v8l9 5 9-5V8M12 13v8" /></svg>;
}

function isSingleSelect(field: QuestionField) {
  return ["SINGLE_SELECT", "SELECT", "CONFIRM"].includes(field.type.toUpperCase()) && (field.options?.length ?? 0) > 0;
}

function QuestionCard({ value, disabled, onSubmit, onCancel }: { value: QuestionCardState; disabled: boolean; onSubmit: (answers: Record<string, string>) => void; onCancel: () => void }) {
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [customValues, setCustomValues] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [invalidField, setInvalidField] = useState<string | null>(null);
  useEffect(() => { setAnswers({}); setCustomValues({}); setFormError(null); setInvalidField(null); }, [value.questionId, value.version, value.fields]);
  const submit = () => {
    const next: Record<string, string> = {};
    for (const field of value.fields) {
      const raw = answers[field.name] === "__OTHER__" ? customValues[field.name] ?? "" : answers[field.name] ?? "";
      const normalized = raw.trim();
      if (field.required && !normalized) { setInvalidField(field.name); setFormError(`请先完成“${field.label}”。`); return; }
      if (normalized) next[field.name] = normalized.slice(0, field.maxLength ?? 4_000);
    }
    setInvalidField(null); setFormError(null); onSubmit(next);
  };
  const keyboard = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "Escape") { event.preventDefault(); onCancel(); }
    else if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.requestSubmit(); }
  };
  const finalConfirmation = value.step === "CONFIRM";
  const stageLabel = ({ INTENT: "选择售后事项", ORDER_SELECT: "选择订单", REASON: "补充退款原因", CONFIRM: "最终确认", HISTORY_ACTION: "选择记录操作" } as Record<string, string>)[value.step ?? ""] ?? value.step ?? "等待输入";
  return <form className="decision-card question-card" onSubmit={(event) => { event.preventDefault(); submit(); }} onKeyDown={keyboard}>
    <div className="decision-heading"><span className="question-heading-icon"><CheckCircle2 aria-hidden="true" /></span><div className="question-heading-copy"><span className="question-status">需要确认</span><h2>{value.title}</h2><div className="question-meta"><span>业务阶段：{stageLabel}</span>{value.stepNo !== undefined ? <span>第 {value.stepNo} 步</span> : null}<span>检查点 v{value.version}</span></div></div></div>
    {value.operation ? <span className="question-operation"><span className="question-operation-dot" aria-hidden="true" />{value.operation}</span> : null}
    <RestrictedMarkdown value={value.prompt} className="question-prompt" />
    {value.summary && value.summary.length > 0 ? <dl className="question-summary">{value.summary.map((line) => <div key={`${line.label}-${line.value}`}><dt>{line.label}</dt><dd>{line.value}</dd></div>)}</dl> : null}
    <div className="question-fields">{value.fields.map((field, index) => {
      const fieldValue = answers[field.name] ?? ""; const otherSelected = fieldValue === "__OTHER__";
      const controlProps = { autoFocus: index === 0, "aria-label": field.label, "aria-invalid": invalidField === field.name, disabled, maxLength: field.maxLength ?? 4_000, value: fieldValue, onChange: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => setAnswers((current) => ({ ...current, [field.name]: event.target.value })) };
      return <label className="question-field" key={field.name}><span className="question-field-label">{field.label}{field.required ? <em aria-hidden="true">必填</em> : null}</span>
        {isSingleSelect(field) ? <select {...controlProps}><option value="">请选择</option>{(field.options ?? []).slice(0, 3).map((option) => <option value={option} key={option}>{option}</option>)}{field.allowCustom ? <option value="__OTHER__">其他</option> : null}</select> : field.name.toLowerCase().includes("reason") ? <textarea {...controlProps} rows={3} placeholder="请说明退款原因" /> : <input {...controlProps} placeholder="请填写" />}
        {otherSelected ? <input aria-label={`${field.label}自定义内容`} autoFocus disabled={disabled} aria-invalid={invalidField === field.name} maxLength={field.maxLength ?? 4_000} value={customValues[field.name] ?? ""} onChange={(event) => setCustomValues((current) => ({ ...current, [field.name]: event.target.value }))} placeholder="请补充具体内容" /> : null}
      </label>;
    })}</div>
    {formError ? <p className="question-form-error" role="alert"><CircleAlert aria-hidden="true" />{formError}</p> : null}
    <div className="actions question-actions"><button type="submit" disabled={disabled}><span className="question-action-icon"><Check aria-hidden="true" /></span>{disabled ? "处理中…" : finalConfirmation ? "确认并执行" : "继续"}</button><button className="secondary" type="button" disabled={disabled} onClick={onCancel}><span className="question-action-icon question-action-icon-secondary"><X aria-hidden="true" /></span>结束本次操作</button><span className="question-key-help">Enter 提交 · Shift + Enter 换行 · Esc 结束</span></div>
  </form>;
}

function activityIcon(status: BusinessProgressStatus) {
  if (status === "ERROR") return <CircleAlert aria-hidden="true" />;
  if (status === "ACTIVE" || status === "WAITING") return <Clock3 aria-hidden="true" />;
  return <CheckCircle2 aria-hidden="true" />;
}

function turnSummary(turn: ThreadViewTurn) {
  const last = turn.activities.at(-1);
  if (last) return last.label;
  if (turn.status === "ACTIVE" || turn.status === "QUEUED") return "正在分析请求";
  return statusLabel(turn.status);
}

function duration(turn: ThreadViewTurn) {
  if (!turn.finishedAt) return null;
  const ms = Math.max(0, new Date(turn.finishedAt).valueOf() - new Date(turn.startedAt).valueOf());
  return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)}s`;
}

function Turn({ turn, busy, retryingRunId, onRetry, onInspect, onAction, onAnswer, onCancel }: { turn: ThreadViewTurn; busy: boolean; retryingRunId: string | null; onRetry: (runId: string) => void; onInspect: (turnId: string) => void; onAction: (sourceTurnId: string, orderId: string, actionType: OrderActionType) => void; onAnswer: (answers: Record<string, string>) => void; onCancel: () => void }) {
  const retryable = turn.externalActionStatus === "MANUAL_RETRY_REQUIRED" && turn.workflowRunId;
  const hasStructuredFacts = turn.orderCards.length > 0 || turn.logisticsTimelines.length > 0;
  return <article className={`conversation-turn thread-turn turn-${turn.status.toLowerCase()}`}>
    <div className="turn-request"><span className="turn-avatar user-avatar">你</span><div><div className="turn-meta"><strong>你的请求</strong><time>{time(turn.startedAt)}</time><span className={`turn-status status-${turn.status.toLowerCase()}`}>{statusLabel(turn.status)}</span></div><p>{turn.userMessage}</p></div></div>
    <div className="turn-response"><span className="turn-avatar agent-avatar"><Bot aria-label="Agent" /></span><div className="turn-response-body"><div className="turn-meta turn-response-meta"><strong>售后助手</strong><span className="turn-route">业务流</span><button className="detail-trigger" type="button" onClick={() => onInspect(turn.turnId)}><PanelRight aria-hidden="true" />运行详情 <span>{turn.items.length}</span></button></div>
      <div className={`turn-summary summary-${turn.activities.at(-1)?.status?.toLowerCase() ?? "active"}`}><span className="summary-icon">{activityIcon(turn.activities.at(-1)?.status ?? "ACTIVE")}</span><strong>{turnSummary(turn)}</strong>{duration(turn) ? <time>{duration(turn)}</time> : null}</div>
      {hasStructuredFacts ? <OrderResults turn={turn} disabled={busy} onAction={onAction} /> : turn.content ? <RestrictedMarkdown value={turn.content} className="agent-content" /> : turn.status === "ACTIVE" || turn.status === "QUEUED" ? <p className="agent-content loading-copy">正在分析你的请求…</p> : null}
      {turn.question ? <QuestionCard key={`${turn.question.runId}:${turn.question.questionId}`} value={turn.question} disabled={busy} onSubmit={onAnswer} onCancel={onCancel} /> : null}
      {turn.externalActionReceipt?.verificationStatus === "PENDING" ? <div className="action-receipt action-receipt-pending"><div><strong>操作已受理、最新状态暂未核验</strong><span>{turn.externalActionReceipt.verificationMessage ?? "可以重新查询该订单的最新事实。"}</span></div>{turn.externalActionReceipt.orderId ? <button className="secondary" type="button" disabled={busy} onClick={() => onAction(turn.turnId, turn.externalActionReceipt?.orderId as string, "REFRESH_ORDER")}>重新查询最新状态</button> : null}</div> : null}
      {turn.externalActionReceipt?.verificationStatus === "VERIFIED" ? <p className="action-receipt action-receipt-verified"><CheckCircle2 aria-hidden="true" />{turn.externalActionReceipt.verificationMessage ?? "最新订单状态已核验"}</p> : null}
      {turn.error ? <p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{turn.error}</p> : null}
      {retryable ? <div className="actions turn-actions"><button className="secondary" type="button" disabled={busy || retryingRunId === turn.workflowRunId} onClick={() => onRetry(turn.workflowRunId as string)}><RotateCcw aria-hidden="true" />{retryingRunId === turn.workflowRunId ? "重试已排队" : "人工重试"}</button></div> : null}
    </div></div>
  </article>;
}

const ITEM_LABELS: Record<string, string> = { USER_MESSAGE: "用户请求", TURN_STATE: "Turn 状态", ASSISTANT_MESSAGE: "助手回复", TOOL_CALL: "工具调用", TOOL_RESULT: "工具结果", WORKFLOW_STARTED: "Workflow 启动", WORKFLOW_QUESTION: "确认卡片", WORKFLOW_ANSWER: "确认回答", WORKFLOW_RESULT: "Workflow 回执", EXTERNAL_ACTION_STATUS: "外部动作", ORDER_LIST: "订单列表", ORDER_DETAIL: "订单详情", LOGISTICS_TIMELINE: "物流时间线", ORDER_ACTION_REQUEST: "订单动作", EXECUTION_EVENT: "执行记录", ERROR: "错误" };

function safeJsonPreview(item: AgentItem) {
  const redact = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(redact);
    if (!value || typeof value !== "object") return value;
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, nested]) => /token|secret|password|authorization|apiKey|userId/i.test(key) ? [key, "[已隐藏]"] : [key, redact(nested)]));
  };
  if (typeof item.payload.data === "string") return item.payload.data;
  try { return JSON.stringify(redact(item.payload.data), null, 2); } catch { return "无法展示该 Item"; }
}

function ItemInspector({ turn, replayStatus, onClose }: { turn: ThreadViewTurn | null; replayStatus: "idle" | "loading" | "loaded" | "failed"; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!turn) return;
    const previousOverflow = document.body.style.overflow;
    const onKeyDown = (event: globalThis.KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKeyDown);
    if (window.matchMedia?.("(max-width: 1179px)").matches) document.body.style.overflow = "hidden";
    closeRef.current?.focus();
    return () => { document.removeEventListener("keydown", onKeyDown); document.body.style.overflow = previousOverflow; };
  }, [onClose, turn]);
  if (!turn) return null;
  return <><button className="inspector-backdrop" type="button" aria-label="关闭运行详情" onClick={onClose} /><aside className="item-inspector" aria-label="Item 序列检查器">
    <header className="inspector-heading"><div><span className="eyebrow">ITEM INSPECTOR</span><h2>运行详情</h2><p>{turn.items.length} 个持久化 Item · Turn {turn.turnId.slice(0, 8)}</p>{replayStatus === "loading" ? <small className="inspector-replay-note">正在加载终态回放…</small> : replayStatus === "failed" ? <small className="inspector-replay-note inspector-replay-failed">回放暂不可用，已显示当前已恢复事实。</small> : null}</div><button ref={closeRef} className="secondary compact-icon" type="button" aria-label="关闭运行详情" onClick={onClose}><X aria-hidden="true" /></button></header>
    <div className="inspector-mode"><strong>Item 序列</strong><span>{turn.items.length}</span></div>
    <ol className="item-sequence">{turn.items.map((item) => <li key={item.itemId} className={`item-row item-${item.type.toLowerCase()}`}><div className="item-row-meta"><span className="item-sequence-no">#{String(item.sequence).padStart(3, "0")}</span><strong>{ITEM_LABELS[item.type] ?? item.type}</strong><time>{dateTime(item.createdAt)}</time></div><pre>{safeJsonPreview(item)}</pre></li>)}</ol>
  </aside></>;
}

export function ThreadWorkspace({ workspace, userId }: Props) {
  const [message, setMessage] = useState("");
  const [threadView, setThreadView] = useState<ThreadStatus>("ACTIVE");
  const [editingThreadId, setEditingThreadId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState("");
  const [threadQuery, setThreadQuery] = useState("");
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [inspectedTurnId, setInspectedTurnId] = useState<string | null>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const allThreads = [...workspace.threads, ...workspace.archivedThreads];
  const currentThread = allThreads.find((item) => item.threadId === workspace.threadId);
  const visibleThreads = threadView === "ACTIVE" ? workspace.threads : workspace.archivedThreads;
  const normalizedThreadQuery = threadQuery.trim().toLocaleLowerCase();
  const filteredThreads = normalizedThreadQuery ? visibleThreads.filter((item) => `${item.title} ${item.contextId ?? ""}`.toLocaleLowerCase().includes(normalizedThreadQuery)) : visibleThreads;
  const question = workspace.question;
  const questionInTurn = Boolean(question && workspace.turns.some((turn) => turn.question?.questionId === question.questionId));
  const readOnly = currentThread?.status === "ARCHIVED";
  const inputDisabled = workspace.busy || Boolean(question) || readOnly || !workspace.threadId;
  const inspectedTurn = workspace.turns.find((turn) => turn.turnId === inspectedTurnId) ?? null;
  const inspect = (turnId: string) => { setInspectedTurnId(turnId); void workspace.loadExecution(turnId); };
  const submit = (event: FormEvent) => { event.preventDefault(); const next = message.trim(); if (!next) return; void workspace.send(next); setMessage(""); };
  const keyboard = (event: KeyboardEvent<HTMLTextAreaElement>) => { if (event.nativeEvent.isComposing) return; if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } };
  const chooseView = (nextView: ThreadStatus) => { setThreadView(nextView); setEditingThreadId(null); if (nextView === "ARCHIVED") void workspace.loadArchivedThreads(); };
  const chooseThread = (nextThreadId: string) => { setEditingThreadId(null); setMobileSidebarOpen(false); setInspectedTurnId(null); workspace.selectThread(nextThreadId); };
  const startRename = (item: { threadId: string; title: string }) => { setEditingThreadId(item.threadId); setDraftTitle(item.title); };
  const cancelRename = () => { setEditingThreadId(null); setDraftTitle(""); };
  const saveRename = async () => { if (!editingThreadId || !draftTitle.trim()) { cancelRename(); return; } if (await workspace.rename(editingThreadId, draftTitle)) cancelRename(); };
  const archive = async (nextThreadId: string) => { setEditingThreadId(null); await workspace.archiveThread(nextThreadId); };
  const restore = async (nextThreadId: string) => { setEditingThreadId(null); await workspace.restoreThread(nextThreadId); setThreadView("ACTIVE"); setMobileSidebarOpen(false); };
  const create = async () => { setThreadView("ACTIVE"); setMobileSidebarOpen(false); await workspace.createThread(); };
  return <section className={`thread-workspace ${inspectedTurn ? "has-inspector" : ""}`} aria-label="订单调度台">
    <div className="thread-layout">
      {mobileSidebarOpen ? <button className="thread-sidebar-backdrop" type="button" aria-label="关闭对话列表" onClick={() => setMobileSidebarOpen(false)} /> : null}
      <aside className={`thread-sidebar ${mobileSidebarOpen ? "mobile-open" : ""}`} aria-label="对话列表">
        <div className="sidebar-heading"><div><span className="eyebrow">THREADS</span><h2>对话记录</h2></div><div className="sidebar-heading-actions"><button className="secondary compact-icon" type="button" onClick={() => void create()} aria-label="新建对话"><ListPlus aria-hidden="true" /></button><button className="secondary compact-icon mobile-sidebar-close" type="button" onClick={() => setMobileSidebarOpen(false)} aria-label="关闭对话列表"><X aria-hidden="true" /></button></div></div>
        <div className="thread-tabs" role="tablist" aria-label="对话状态"><button type="button" role="tab" aria-selected={threadView === "ACTIVE"} className={threadView === "ACTIVE" ? "selected" : ""} onClick={() => chooseView("ACTIVE")}>进行中 <span>{workspace.threads.length}</span></button><button type="button" role="tab" aria-selected={threadView === "ARCHIVED"} className={threadView === "ARCHIVED" ? "selected" : ""} onClick={() => chooseView("ARCHIVED")}>回收站 <span>{workspace.archivedThreads.length}</span></button></div>
        <div className="thread-search"><Search aria-hidden="true" /><input type="search" aria-label="搜索对话" value={threadQuery} onChange={(event) => setThreadQuery(event.target.value)} placeholder="搜索对话" /></div>
        <div className="thread-list">{workspace.archiveLoading && threadView === "ARCHIVED" ? <p className="thread-list-empty">正在读取回收站…</p> : null}{!workspace.archiveLoading && filteredThreads.length === 0 ? <p className="thread-list-empty">{normalizedThreadQuery ? "没有匹配的对话。" : threadView === "ACTIVE" ? "还没有进行中的对话。" : "回收站还是空的。"}</p> : null}{filteredThreads.map((item) => <div className={`thread-row ${item.threadId === workspace.threadId ? "selected" : ""}`} key={item.threadId}>
          {editingThreadId === item.threadId ? <input className="thread-row-title-input" aria-label={`重命名 ${item.title}`} autoFocus value={draftTitle} onChange={(event) => setDraftTitle(event.target.value)} onBlur={cancelRename} onKeyDown={(event) => { if (event.nativeEvent.isComposing) return; if (event.key === "Escape") { event.preventDefault(); cancelRename(); } if (event.key === "Enter") { event.preventDefault(); void saveRename(); } }} /> : <button type="button" className="thread-row-select" onClick={() => chooseThread(item.threadId)}><span><strong>{item.title}</strong><small>{item.contextId ?? "订单售后"}</small></span></button>}
          <div className="thread-row-side"><span className={`status status-${item.status.toLowerCase()}`}>{item.status === "ACTIVE" ? "进行中" : "已归档"}</span><div className="thread-row-actions">{editingThreadId !== item.threadId ? <button className="thread-row-action" type="button" aria-label="重命名对话" onClick={() => startRename(item)}><Pencil aria-hidden="true" /></button> : null}{item.status === "ACTIVE" ? <button className="thread-row-action archive-action" type="button" aria-label="归档对话" disabled={workspace.busy || Boolean(question)} onClick={() => void archive(item.threadId)}><Archive aria-hidden="true" /></button> : <button className="thread-row-action" type="button" aria-label="恢复对话" onClick={() => void restore(item.threadId)}><ArchiveRestore aria-hidden="true" /></button>}</div></div>
        </div>)}</div>
        <div className="thread-sidebar-note"><GitBranch aria-hidden="true" /><p>订单事实、确认记录和处理结果会留在对应 Turn；归档只收起记录。</p></div>
      </aside>
      <main className="thread-main">
        <div className="thread-context-bar"><div><span className="eyebrow">CURRENT THREAD</span><strong>{currentThread?.title ?? "加载中…"}</strong><span className="thread-context-summary">{currentThread?.contextId ?? "订单售后"} · {workspace.turns.length} 个请求</span></div><div className="thread-context-actions"><button className="secondary icon-button mobile-thread-toggle" type="button" onClick={() => setMobileSidebarOpen(true)}><Menu aria-hidden="true" />对话列表</button><span className={`status status-${currentThread?.status?.toLowerCase() ?? "active"}`}>{readOnly ? "已归档" : "进行中"}</span>{readOnly && currentThread ? <button className="secondary compact-action" type="button" onClick={() => void restore(currentThread.threadId)}><ArchiveRestore aria-hidden="true" />恢复对话</button> : <span className="account-chip">{userId}</span>}</div></div>
        {workspace.error ? <p className="workspace-alert" role="alert"><CircleAlert aria-hidden="true" />{workspace.error}</p> : null}
        <div className="thread-records">{workspace.loading ? <div className="conversation-empty"><Bot aria-hidden="true" /><h2>正在恢复对话</h2><p>正在读取订单事实和历史结果。</p></div> : workspace.turns.length === 0 ? <div className="conversation-empty"><ShieldCheck aria-hidden="true" /><h2>直接输入请求</h2><p>可以直接输入订单号、物流问题或售后诉求。</p></div> : <>{workspace.turns.map((turn) => <Turn key={turn.turnId} turn={turn} busy={workspace.busy} retryingRunId={workspace.retryingRunId} onRetry={workspace.retry} onInspect={inspect} onAction={(sourceTurnId, orderId, actionType) => void workspace.orderAction(sourceTurnId, orderId, actionType)} onAnswer={(answers) => void workspace.answer(answers)} onCancel={() => void workspace.answer({}, "CANCEL")} />)}{question && !questionInTurn ? <QuestionCard value={question} disabled={workspace.busy} onSubmit={(answers) => void workspace.answer(answers)} onCancel={() => void workspace.answer({}, "CANCEL")} /> : null}</>}</div>
        {readOnly && currentThread ? <div className="composer composer-readonly"><ArchiveRestore aria-hidden="true" /><div><strong>这段对话已归档</strong><span>恢复后才能继续查询订单或发起售后操作。</span></div><button className="secondary" type="button" onClick={() => void restore(currentThread.threadId)}>恢复对话</button></div> : question ? null : <form className="composer" onSubmit={submit}><label htmlFor="thread-message">输入请求</label><textarea ref={composerRef} id="thread-message" value={message} disabled={inputDisabled} onKeyDown={keyboard} onChange={(event) => setMessage(event.target.value)} placeholder="输入订单号、物流问题或售后诉求…" /><div className="composer-actions"><span>Enter 发送 · Shift + Enter 换行</span>{workspace.busy ? <button className="secondary icon-button" type="button" onClick={() => void workspace.cancel()}><Square aria-hidden="true" />取消处理</button> : <button className="icon-button" type="submit" disabled={!message.trim() || inputDisabled}><Send aria-hidden="true" />发送</button>}</div></form>}
      </main>
      <ItemInspector turn={inspectedTurn} replayStatus={inspectedTurnId ? workspace.executionReplayStates[inspectedTurnId] ?? "idle" : "idle"} onClose={() => setInspectedTurnId(null)} />
    </div>
  </section>;
}
