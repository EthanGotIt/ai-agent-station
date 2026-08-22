import { Fragment, useEffect, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { Archive, ArchiveRestore, Bot, CheckCircle2, CircleAlert, GitBranch, ListPlus, Menu, PackageSearch, Pencil, Plus, Send, ShieldCheck, Square, Truck, Undo2, X } from "lucide-react";
import type { BusinessProgress, BusinessProgressStatus, LogisticsTimeline, OrderCard, QuestionCardState, QuestionField, ThreadStatus } from "./threadTypes";
import type { useThreadWorkspace } from "./useThreadWorkspace";

type Props = { workspace: ReturnType<typeof useThreadWorkspace>; userId: string };

function statusLabel(status: string) {
  return ({
    QUEUED: "排队中",
    ACTIVE: "处理中",
    WAITING_USER_INPUT: "等待确认",
    WAITING_EXTERNAL_ACTION: "处理中",
    COMPLETED: "已完成",
    CANCELLED: "已取消",
    TIMED_OUT: "已超时",
    FAILED: "失败",
    MANUAL_RETRY_REQUIRED: "需要重试"
  } as Record<string, string>)[status] ?? status;
}

function time(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function dateTime(value: string | null) {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? "—" : new Intl.DateTimeFormat("zh-CN", {
    month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit"
  }).format(parsed);
}

function amount(order: OrderCard) {
  if (order.paidAmount === null) return "金额未知";
  return `${order.currency ?? "¥"} ${order.paidAmount.toFixed(2)}`;
}

function orderStatus(status: string) {
  return ({
    PAID: "已支付", SHIPPED: "运输中", DELIVERED: "已送达", CANCELLED: "已取消", REFUNDED: "已退款"
  } as Record<string, string>)[status] ?? status;
}

function OrderResults({
  orders,
  timelines,
  disabled,
  onAction
}: {
  orders: OrderCard[];
  timelines: LogisticsTimeline[];
  disabled: boolean;
  onAction: (message: string) => void;
}) {
  if (orders.length === 0 && timelines.length === 0) return null;
  const timelineByOrder = new Map(timelines.map((timeline) => [timeline.orderId, timeline]));
  const visibleOrderIds = new Set(orders.map((order) => order.orderId));
  const orphanTimelines = timelines.filter((timeline) => !visibleOrderIds.has(timeline.orderId));
  return <section className="order-results" aria-label="订单事实">
    {orders.length > 0 ? <div className="result-heading"><div><span className="eyebrow">ORDER FACTS</span><h2>找到的订单</h2></div><span>{orders.length} 条</span></div> : null}
    {orders.length > 0 ? <div className="order-card-grid">{orders.map((order) => {
      const timeline = timelineByOrder.get(order.orderId);
      return <article className="order-card" key={order.orderId}>
        <div className="order-card-heading"><div><strong>{order.itemSummary ?? "订单商品"}</strong><span>{order.orderId}</span></div><span className={`status status-${order.status.toLowerCase()}`}>{orderStatus(order.status)}</span></div>
        <div className="order-card-meta"><span>{amount(order)}</span><span>下单 {dateTime(order.createdAt)}</span><span>{order.visibility === "HIDDEN" ? "已隐藏" : order.logisticsStatus ?? "暂无物流状态"}</span></div>
        {timeline ? <LogisticsTimelineView timeline={timeline} /> : null}
        <div className="order-card-actions" aria-label={`${order.orderId} 可用操作`}>
          <button type="button" className="secondary" disabled={disabled} onClick={() => onAction(`查询订单 ${order.orderId} 的物流状态`)}><Truck aria-hidden="true" />查物流</button>
          {order.status !== "REFUNDED" && order.status !== "CANCELLED" ? <button type="button" className="secondary" disabled={disabled} onClick={() => onAction(`我想申请退款，订单是 ${order.orderId}`)}><Undo2 aria-hidden="true" />申请退款</button> : null}
          {order.status === "PAID" ? <button type="button" className="secondary" disabled={disabled} onClick={() => onAction(`请催发货，订单是 ${order.orderId}`)}><PackageSearch aria-hidden="true" />催发货</button> : null}
          {order.visibility === "HIDDEN"
            ? <button type="button" className="secondary" disabled={disabled} onClick={() => onAction(`请恢复订单 ${order.orderId} 到订单历史记录`)}><ArchiveRestore aria-hidden="true" />恢复记录</button>
            : <button type="button" className="secondary" disabled={disabled} onClick={() => onAction(`请把订单 ${order.orderId} 隐藏到订单历史记录`)}><Archive aria-hidden="true" />隐藏记录</button>}
        </div>
      </article>;
    })}</div> : null}
    {orphanTimelines.map((timeline) => <article className="order-card" key={timeline.orderId}><div className="order-card-heading"><div><strong>物流时间线</strong><span>{timeline.orderId}</span></div></div><LogisticsTimelineView timeline={timeline} /></article>)}
  </section>;
}

function LogisticsTimelineView({ timeline }: { timeline: LogisticsTimeline }) {
  if (timeline.events.length === 0) return <p className="timeline-empty">暂时没有可展示的物流节点。</p>;
  return <ol className="logistics-timeline" aria-label={`${timeline.orderId} 物流时间线`}>
    {timeline.events.map((event) => <li key={event.eventId}><span className="timeline-dot" /><div><div><strong>{event.status}</strong><time>{dateTime(event.occurredAt)}</time></div><span>{event.location || "物流节点"}</span><p>{event.description}</p></div></li>)}
  </ol>;
}

function inlineMarkdown(value: string, keyPrefix: string): ReactNode[] {
  return value.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, index) => {
    if (part.startsWith("**") && part.endsWith("**")) {
      return <strong key={`${keyPrefix}-strong-${index}`}>{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith("`") && part.endsWith("`")) {
      return <code key={`${keyPrefix}-code-${index}`}>{part.slice(1, -1)}</code>;
    }
    return <Fragment key={`${keyPrefix}-text-${index}`}>{part}</Fragment>;
  });
}

/** 只渲染粗体、行内代码和换行；所有其他标记作为普通文本保留。 */
function RestrictedMarkdown({ value, className }: { value: string; className: string }) {
  return <div className={className}>
    {value.split(/\n{2,}/).map((block, blockIndex) => <p key={`block-${blockIndex}`}>
      {block.split("\n").map((line, lineIndex) => <Fragment key={`line-${blockIndex}-${lineIndex}`}>
        {lineIndex > 0 ? <br /> : null}
        {inlineMarkdown(line, `line-${blockIndex}-${lineIndex}`)}
      </Fragment>)}
    </p>)}
  </div>;
}

function isSingleSelect(field: QuestionField) {
  return ["SINGLE_SELECT", "SELECT", "CONFIRM"].includes(field.type.toUpperCase()) && (field.options?.length ?? 0) > 0;
}

function initialAnswers(fields: QuestionField[]) {
  return fields.reduce<Record<string, string>>((answers, field) => {
    if (field.name === "decision" && field.options?.includes("APPROVE")) answers[field.name] = "APPROVE";
    return answers;
  }, {});
}

function QuestionCard({
  value,
  disabled,
  onSubmit,
  onCancel
}: {
  value: QuestionCardState;
  disabled: boolean;
  onSubmit: (answers: Record<string, string>) => void;
  onCancel: () => void;
}) {
  const [answers, setAnswers] = useState<Record<string, string>>(() => initialAnswers(value.fields));
  const [customValues, setCustomValues] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    setAnswers(initialAnswers(value.fields));
    setCustomValues({});
    setFormError(null);
  }, [value.questionId, value.version, value.fields]);

  const submit = () => {
    const next: Record<string, string> = {};
    for (const field of value.fields) {
      const raw = answers[field.name] === "__OTHER__" ? customValues[field.name] ?? "" : answers[field.name] ?? "";
      const normalized = raw.trim();
      if (field.required && !normalized) {
        setFormError(`请先完成“${field.label}”。`);
        return;
      }
      if (normalized) next[field.name] = normalized.slice(0, field.maxLength ?? 4_000);
    }
    setFormError(null);
    onSubmit(next);
  };

  const keyboard = (event: KeyboardEvent<HTMLFormElement>) => {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "Escape") {
      event.preventDefault();
      onCancel();
      return;
    }
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      event.currentTarget.requestSubmit();
    }
  };

  return <form className="decision-card question-card" onSubmit={(event) => { event.preventDefault(); submit(); }} onKeyDown={keyboard}>
    <div className="decision-heading">
      <CheckCircle2 aria-hidden="true" />
      <div><h2>{value.title}</h2><p>请确认后继续 · 检查点版本 {value.version}</p></div>
    </div>
    <RestrictedMarkdown value={value.prompt} className="question-prompt" />
    {value.summary && value.summary.length > 0 ? <dl className="question-summary">
      {value.summary.map((line) => <div key={`${line.label}-${line.value}`}><dt>{line.label}</dt><dd>{line.value}</dd></div>)}
    </dl> : null}
    <div className="question-fields">
      {value.fields.map((field, index) => {
        const fieldValue = answers[field.name] ?? "";
        const otherSelected = fieldValue === "__OTHER__";
        return <label className="question-field" key={field.name}>
          <span>{field.label}{field.required ? <em aria-hidden="true">必填</em> : null}</span>
          {isSingleSelect(field) ? <select
            autoFocus={index === 0}
            aria-label={field.label}
            disabled={disabled}
            value={fieldValue}
            onChange={(event) => setAnswers((current) => ({ ...current, [field.name]: event.target.value }))}
          >
            <option value="">请选择</option>
            {(field.options ?? []).slice(0, 3).map((option) => <option value={option} key={option}>{option}</option>)}
            {field.allowCustom ? <option value="__OTHER__">其他</option> : null}
          </select> : <input
            autoFocus={index === 0}
            aria-label={field.label}
            disabled={disabled}
            maxLength={field.maxLength ?? 4_000}
            value={fieldValue}
            onChange={(event) => setAnswers((current) => ({ ...current, [field.name]: event.target.value }))}
            placeholder="请填写"
          />}
          {otherSelected ? <input
            aria-label={`${field.label}自定义内容`}
            autoFocus
            disabled={disabled}
            maxLength={field.maxLength ?? 4_000}
            value={customValues[field.name] ?? ""}
            onChange={(event) => setCustomValues((current) => ({ ...current, [field.name]: event.target.value }))}
            placeholder="请补充具体内容"
          /> : null}
        </label>;
      })}
    </div>
    {formError ? <p className="question-form-error" role="alert"><CircleAlert aria-hidden="true" />{formError}</p> : null}
    <div className="actions question-actions">
      <button type="submit" disabled={disabled}><CheckCircle2 aria-hidden="true" />提交回答</button>
      <button className="secondary" type="button" disabled={disabled} onClick={onCancel}><X aria-hidden="true" />取消操作</button>
      <span className="question-key-help">Enter 提交 · Shift + Enter 换行 · Esc 取消</span>
    </div>
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
    <div className="turn-request"><span className="turn-avatar user-avatar">你</span><div><div className="turn-meta"><strong>你的请求</strong><time>{time(turn.startedAt)}</time><span className={`turn-status status-${turn.status.toLowerCase()}`}>{statusLabel(turn.status)}</span></div><p>{turn.userMessage}</p></div></div>
    <div className="turn-response"><span className="turn-avatar agent-avatar"><Bot aria-label="Agent" /></span><div><div className="turn-meta"><strong>售后助手</strong><span className="turn-route">订单服务</span></div>{turn.content ? <RestrictedMarkdown value={turn.content} className="agent-content" /> : turn.status === "ACTIVE" || turn.status === "QUEUED" ? <p className="agent-content loading-copy">正在分析你的请求…</p> : null}{turn.error ? <p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{turn.error}</p> : null}{retryable ? <div className="actions turn-actions"><button className="secondary" type="button" disabled={busy || retryingRunId === turn.workflowRunId} onClick={() => onRetry(turn.workflowRunId as string)}>{retryingRunId === turn.workflowRunId ? "重试已排队" : "人工重试"}</button></div> : null}</div></div>
  </article>;
}

function progressIcon(status: BusinessProgressStatus) {
  return status === "ERROR" ? <CircleAlert aria-hidden="true" /> : <CheckCircle2 aria-hidden="true" />;
}

function ProgressPanel({ progress, error }: { progress: BusinessProgress[]; error: string | null }) {
  if (progress.length === 0 && !error) return null;
  return <section className="business-progress" aria-label="处理进度">
    <div className="progress-heading"><div><span className="eyebrow">ORDER SERVICE</span><h2>这次请求的处理进度</h2></div><span className="progress-live">实时更新</span></div>
    {error ? <p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{error}</p> : null}
    <ol className="progress-list">
      {progress.map((step) => <li className={`progress-step progress-${step.status.toLowerCase()}`} key={step.id}>
        <span className="progress-icon">{progressIcon(step.status)}</span>
        <div><strong>{step.label}</strong>{step.detail ? <span>{step.detail}</span> : null}</div>
      </li>)}
    </ol>
  </section>;
}

export function ThreadWorkspace({ workspace, userId }: Props) {
  const [message, setMessage] = useState("");
  const [threadView, setThreadView] = useState<ThreadStatus>("ACTIVE");
  const [editingThreadId, setEditingThreadId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState("");
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const allThreads = [...workspace.threads, ...workspace.archivedThreads];
  const currentThread = allThreads.find((item) => item.threadId === workspace.threadId);
  const visibleThreads = threadView === "ACTIVE" ? workspace.threads : workspace.archivedThreads;
  const question = workspace.question;
  const readOnly = currentThread?.status === "ARCHIVED";
  const inputDisabled = workspace.busy || Boolean(question) || readOnly || !workspace.threadId;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const next = message.trim();
    if (!next) return;
    void workspace.send(next);
    setMessage("");
  };

  const keyboard = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  };

  const chooseView = (nextView: ThreadStatus) => {
    setThreadView(nextView);
    setEditingThreadId(null);
    if (nextView === "ARCHIVED") void workspace.loadArchivedThreads();
  };

  const chooseThread = (nextThreadId: string) => {
    setEditingThreadId(null);
    setMobileSidebarOpen(false);
    workspace.selectThread(nextThreadId);
  };

  const startRename = (item: { threadId: string; title: string }) => {
    setEditingThreadId(item.threadId);
    setDraftTitle(item.title);
  };

  const cancelRename = () => {
    setEditingThreadId(null);
    setDraftTitle("");
  };

  const saveRename = async () => {
    if (!editingThreadId || !draftTitle.trim()) {
      cancelRename();
      return;
    }
    const saved = await workspace.rename(editingThreadId, draftTitle);
    if (saved) cancelRename();
  };

  const archive = async (nextThreadId: string) => {
    setEditingThreadId(null);
    await workspace.archiveThread(nextThreadId);
  };

  const restore = async (nextThreadId: string) => {
    setEditingThreadId(null);
    await workspace.restoreThread(nextThreadId);
    setThreadView("ACTIVE");
    setMobileSidebarOpen(false);
  };

  const create = async () => {
    setThreadView("ACTIVE");
    setMobileSidebarOpen(false);
    await workspace.createThread();
  };

  return <section className="thread-workspace" aria-labelledby="thread-workspace-heading">
    <div className="workspace-heading">
      <div><p className="eyebrow">订单售后助手</p><h1 id="thread-workspace-heading">把订单售后说清楚，剩下的交给助手</h1><p>查询订单、诊断物流、申请退款或催发货。只有需要你决定的关键一步，才会停下来询问。</p></div>
      <div className="workspace-heading-actions"><button className="secondary icon-button mobile-thread-toggle" type="button" onClick={() => setMobileSidebarOpen(true)}><Menu aria-hidden="true" />对话列表</button><button className="icon-button" type="button" onClick={() => void create()}><Plus aria-hidden="true" />新建对话</button></div>
    </div>
    <div className="thread-layout">
      {mobileSidebarOpen ? <button className="thread-sidebar-backdrop" type="button" aria-label="关闭对话列表" onClick={() => setMobileSidebarOpen(false)} /> : null}
      <aside className={`thread-sidebar ${mobileSidebarOpen ? "mobile-open" : ""}`} aria-label="对话列表">
        <div className="sidebar-heading"><div><span className="eyebrow">我的空间</span><h2>对话记录</h2></div><div className="sidebar-heading-actions"><button className="secondary compact-icon" type="button" onClick={() => void create()} aria-label="新建对话"><ListPlus aria-hidden="true" /></button><button className="secondary compact-icon mobile-sidebar-close" type="button" onClick={() => setMobileSidebarOpen(false)} aria-label="关闭对话列表"><X aria-hidden="true" /></button></div></div>
        <div className="thread-tabs" role="tablist" aria-label="对话状态"><button type="button" role="tab" aria-selected={threadView === "ACTIVE"} className={threadView === "ACTIVE" ? "selected" : ""} onClick={() => chooseView("ACTIVE")}>进行中 <span>{workspace.threads.length}</span></button><button type="button" role="tab" aria-selected={threadView === "ARCHIVED"} className={threadView === "ARCHIVED" ? "selected" : ""} onClick={() => chooseView("ARCHIVED")}>回收站 <span>{workspace.archivedThreads.length}</span></button></div>
        <div className="thread-list">
          {workspace.archiveLoading && threadView === "ARCHIVED" ? <p className="thread-list-empty">正在读取回收站…</p> : null}
          {!workspace.archiveLoading && visibleThreads.length === 0 ? <p className="thread-list-empty">{threadView === "ACTIVE" ? "还没有进行中的对话。" : "回收站还是空的。"}</p> : null}
          {visibleThreads.map((item) => <div className={`thread-row ${item.threadId === workspace.threadId ? "selected" : ""}`} key={item.threadId}>
            {editingThreadId === item.threadId ? <input className="thread-row-title-input" aria-label={`重命名 ${item.title}`} autoFocus value={draftTitle} onChange={(event) => setDraftTitle(event.target.value)} onBlur={cancelRename} onKeyDown={(event) => { if (event.nativeEvent.isComposing) return; if (event.key === "Escape") { event.preventDefault(); cancelRename(); } if (event.key === "Enter") { event.preventDefault(); void saveRename(); } }} /> : <button type="button" className="thread-row-select" onClick={() => chooseThread(item.threadId)}><span><strong>{item.title}</strong><small>{item.contextId ?? "订单售后"}</small></span></button>}
            <div className="thread-row-side"><span className={`status status-${item.status.toLowerCase()}`}>{item.status === "ACTIVE" ? "进行中" : "已归档"}</span><div className="thread-row-actions">{editingThreadId !== item.threadId ? <button className="thread-row-action" type="button" aria-label="重命名对话" onClick={() => startRename(item)}><Pencil aria-hidden="true" /></button> : null}{item.status === "ACTIVE" ? <button className="thread-row-action archive-action" type="button" aria-label="归档对话" disabled={workspace.busy || Boolean(question)} onClick={() => void archive(item.threadId)}><Archive aria-hidden="true" /></button> : <button className="thread-row-action" type="button" aria-label="恢复对话" onClick={() => void restore(item.threadId)}><ArchiveRestore aria-hidden="true" /></button>}</div></div>
          </div>)}
        </div>
        <div className="thread-sidebar-note"><GitBranch aria-hidden="true" /><p>订单事实、确认记录和处理结果都会留在对话里；归档只收起记录，不删除交易或物流信息。</p></div>
      </aside>
      <div className="thread-main">
        <div className="thread-context-bar"><div><span className="eyebrow">当前对话</span><strong>{currentThread?.title ?? "加载中…"}</strong></div><div className="thread-context-actions"><span className={`status status-${currentThread?.status?.toLowerCase() ?? "active"}`}>{readOnly ? "已归档" : "进行中"}</span>{readOnly ? <button className="secondary compact-action" type="button" onClick={() => void restore(currentThread.threadId)}><ArchiveRestore aria-hidden="true" />恢复对话</button> : <span className="account-chip">{userId}</span>}</div></div>
        <div className="quick-start-strip"><span className="eyebrow">可以这样问</span><button type="button" disabled={inputDisabled} onClick={() => void workspace.send("列出今天最新订单")}><PackageSearch aria-hidden="true" />最新订单</button><button type="button" disabled={inputDisabled} onClick={() => void workspace.send("查物流三天没更新的订单")}><Truck aria-hidden="true" />停滞物流</button><button type="button" disabled={inputDisabled} onClick={() => void workspace.send("我想退款，请帮我找出符合条件的订单")}><Undo2 aria-hidden="true" />申请退款</button></div>
        <ProgressPanel progress={workspace.progress} error={workspace.error} />
        <OrderResults orders={workspace.orderCards} timelines={workspace.logisticsTimelines} disabled={inputDisabled} onAction={(nextMessage) => void workspace.send(nextMessage)} />
        <div className="thread-records">{workspace.loading ? <div className="conversation-empty"><Bot aria-hidden="true" /><h2>正在恢复对话</h2><p>正在读取订单事实和历史结果。</p></div> : workspace.turns.length === 0 ? <div className="conversation-empty"><ShieldCheck aria-hidden="true" /><h2>从一个售后问题开始</h2><p>例如“列出今天最新订单”“找物流三天没更新的订单”，也可以直接说“我想退款”。</p></div> : workspace.turns.map((turn) => <Turn key={turn.turnId} turn={turn} busy={workspace.busy} retryingRunId={workspace.retryingRunId} onRetry={workspace.retry} />)}{question ? <QuestionCard key={`${question.runId}:${question.questionId}`} value={question} disabled={workspace.busy} onSubmit={(answers) => void workspace.answer(answers)} onCancel={() => void workspace.answer({ decision: "REJECT" })} /> : null}</div>
        {readOnly ? <div className="composer composer-readonly"><ArchiveRestore aria-hidden="true" /><div><strong>这段对话已归档</strong><span>恢复后才能继续查询订单或发起售后操作。</span></div><button className="secondary" type="button" onClick={() => void restore(currentThread.threadId)}>恢复对话</button></div> : question ? null : <form className="composer" onSubmit={submit}><label htmlFor="thread-message">输入请求</label><textarea id="thread-message" value={message} disabled={inputDisabled} onKeyDown={keyboard} onChange={(event) => setMessage(event.target.value)} placeholder="例如：列出今天最新订单，或找物流三天没更新的订单…" /><div className="composer-actions"><span>Enter 发送 · Shift + Enter 换行</span>{workspace.busy ? <button className="secondary icon-button" type="button" onClick={() => void workspace.cancel()}><Square aria-hidden="true" />取消处理</button> : <button className="icon-button" type="submit" disabled={!message.trim() || inputDisabled}><Send aria-hidden="true" />发送</button>}</div></form>}
      </div>
    </div>
  </section>;
}
