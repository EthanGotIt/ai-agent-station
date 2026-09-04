import { Fragment, memo, useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import {
  Bot,
  Check,
  CheckCircle2,
  ChevronDown,
  CircleAlert,
  CircleHelp,
  Clock3,
  GitBranch,
  ListPlus,
  Menu,
  MoreHorizontal,
  PanelRight,
  Pencil,
  Search,
  Send,
  ShieldCheck,
  Square,
  RefreshCw,
  Trash2,
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
  ThreadViewTurn,
  WorkflowCheckpointState
} from "./threadTypes";
import type { useThreadWorkspace } from "./useThreadWorkspace";
import { OrderActionStatus } from "./OrderActionStatus";
import { findOrderAction, projectOrderAction, type OrderActionRequest } from "./orderActionProjection";

type Props = { workspace: ReturnType<typeof useThreadWorkspace>; userId: string };

function statusLabel(status: string) {
  return ({
    QUEUED: "排队中", ACTIVE: "处理中", WAITING_USER_INPUT: "等待确认", WAITING_EXTERNAL_ACTION: "外部处理中",
    COMPLETED: "已完成", CANCELLED: "已取消", TIMED_OUT: "已超时", FAILED: "失败", MANUAL_RETRY_REQUIRED: "需要人工重试"
  } as Record<string, string>)[status] ?? status;
}

const TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" });
const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });

function time(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? "—" : TIME_FORMATTER.format(parsed);
}

function dateTime(value: string | null) {
  if (!value) return "—";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? "—" : DATE_TIME_FORMATTER.format(parsed);
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

type PendingOrderAction = Omit<OrderActionRequest, "turnId">;

function OrderResults({ turn, disabled, pendingAction, retryingRunId, onRetry, onAction }: { turn: ThreadViewTurn; disabled: boolean; pendingAction: PendingOrderAction | null; retryingRunId: string | null; onRetry: (runId: string) => void; onAction: (sourceTurnId: string, orderId: string, actionType: OrderActionType) => void }) {
  const { orderCards: orders, logisticsTimelines: timelines } = turn;
  if (orders.length === 0 && timelines.length === 0) return null;
  const timelineByOrder = new Map(timelines.map((timeline) => [timeline.orderId, timeline]));
  const visibleOrderIds = new Set(orders.map((order) => order.orderId));
  const orphanTimelines = timelines.filter((timeline) => !visibleOrderIds.has(timeline.orderId));
  return <section className="turn-facts" aria-label={`${turn.turnId} 订单事实`}>
    <div className="result-heading"><div><span className="eyebrow">STRUCTURED FACTS</span><h3>{orders.length > 0 ? `找到 ${orders.length} 个匹配订单` : "物流时间线"}</h3></div><span className="sequence-caption">来自本 Turn</span></div>
    {orders.length > 0 ? <div className="order-card-grid">{orders.map((order) => {
      const timeline = timelineByOrder.get(order.orderId);
      const action = findOrderAction(turn, order.orderId);
      const pending = pendingAction?.sourceTurnId === turn.turnId && pendingAction.orderId === order.orderId ? { ...pendingAction, turnId: "__pending__" } : null;
      const actionView = action ? projectOrderAction(turn, action) : pending ? projectOrderAction(turn, pending) : null;
      const deleted = actionView?.deleted === true;
      return <article className="order-card" key={order.orderId}>
        <div className="order-card-heading"><div><strong>{order.itemSummary ?? "订单商品"}</strong><span>{order.orderId}</span></div><span className={`status ${deleted ? "status-deleted" : `status-${order.status.toLowerCase()}`}`}>{deleted ? "记录已删除" : orderStatus(order.status)}</span></div>
        <div className="order-card-meta"><span>{amount(order)}</span><span>下单 {dateTime(order.createdAt)}</span><span>{deleted ? "记录已删除" : order.logisticsStatus ?? "暂无物流状态"}</span></div>
        {actionView ? <OrderActionStatus view={actionView} disabled={disabled} retrying={retryingRunId === actionView.runId} onRetry={onRetry} onRefresh={(nextOrderId) => onAction(turn.turnId, nextOrderId, "REFRESH_ORDER")} /> : null}
        {timeline ? <LogisticsTimelineView timeline={timeline} /> : null}
        {!deleted ? <div className="order-card-actions" aria-label={`${order.orderId} 可用操作`}>
          <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "QUERY_LOGISTICS")}><Truck aria-hidden="true" />查物流</button>
          {order.status !== "REFUNDED" && order.status !== "CANCELLED" ? <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "REFUND")}><Undo2 aria-hidden="true" />申请退款</button> : null}
          {order.status === "PAID" ? <button type="button" className="secondary" disabled={disabled} aria-busy={disabled} onClick={() => onAction(turn.turnId, order.orderId, "EXPEDITE")}><PackageSearchIcon />催发货</button> : null}
          <details className="order-more-actions">
            <summary className="secondary more-actions-trigger"><MoreHorizontal aria-hidden="true" />更多操作<ChevronDown aria-hidden="true" /></summary>
            <div className="order-more-menu" role="group" aria-label={`${order.orderId} 其他订单操作`}>
              <button type="button" className="secondary danger-action" title="删除后无法恢复" disabled={disabled} aria-busy={disabled} aria-label="删除记录" onClick={() => onAction(turn.turnId, order.orderId, "DELETE_ORDER")}><Trash2 aria-hidden="true" />删除记录<span className="destructive-note">不可恢复</span></button>
            </div>
          </details>
        </div> : null}
      </article>;
    })}</div> : null}
    {orphanTimelines.map((timeline) => {
      const action = findOrderAction(turn, timeline.orderId);
      const pending = pendingAction?.sourceTurnId === turn.turnId && pendingAction.orderId === timeline.orderId ? { ...pendingAction, turnId: "__pending__" } : null;
      const actionView = action ? projectOrderAction(turn, action) : pending ? projectOrderAction(turn, pending) : null;
      return <article className="order-card" key={timeline.orderId}><div className="order-card-heading"><div><strong>物流时间线</strong><span>{timeline.orderId}</span></div></div>{actionView ? <OrderActionStatus view={actionView} disabled={disabled} retrying={retryingRunId === actionView.runId} onRetry={onRetry} onRefresh={(nextOrderId) => onAction(turn.turnId, nextOrderId, "REFRESH_ORDER")} /> : null}<LogisticsTimelineView timeline={timeline} /></article>;
    })}
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
  // 字段结构由问题版本标识；避免每个 SSE Item 重新解析出新数组时清空用户正在填写的答案。
  useEffect(() => { setAnswers({}); setCustomValues({}); setFormError(null); setInvalidField(null); }, [value.questionId, value.version]);
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
  const stageLabel = ({ INTENT: "选择售后事项", ORDER_SELECT: "选择订单", REASON: "补充退款原因", CONFIRM: "最终确认", AUTHORIZE: "授权执行", HISTORY_ACTION: "选择记录操作" } as Record<string, string>)[value.step ?? ""] ?? value.step ?? "等待输入";
  return <form className={`decision-card question-card ${value.legacy ? "question-card-legacy" : ""}`} onSubmit={(event) => { event.preventDefault(); submit(); }} onKeyDown={keyboard}>
    <div className="decision-heading"><span className="question-heading-icon"><CheckCircle2 aria-hidden="true" /></span><div className="question-heading-copy"><span className="question-status">需要补充信息</span><h2 id="question-dialog-title">{value.title}</h2><div className="question-meta"><span>回答目标：{value.resumeTarget === "AGENT" ? "Agent" : "业务流程"}</span><span>回答主题：{stageLabel}</span>{value.stepNo !== undefined ? <span>第 {value.stepNo} 步</span> : null}<span>问题 v{value.version}</span></div></div></div>
    {value.operation ? <span className="question-operation"><span className="question-operation-dot" aria-hidden="true" />{value.operation}</span> : null}
    <RestrictedMarkdown value={value.prompt} className="question-prompt" />
    {value.summary && value.summary.length > 0 ? <dl className="question-summary">{value.summary.map((line) => <div key={`${line.label}-${line.value}`}><dt>{line.label}</dt><dd>{line.value}</dd></div>)}</dl> : null}
    <div className="question-fields">{value.fields.map((field, index) => {
        const fieldValue = answers[field.name] ?? ""; const otherSelected = fieldValue === "__OTHER__";
        const controlProps = { autoFocus: index === 0, "aria-label": field.label, "aria-invalid": invalidField === field.name, "aria-required": field.required, "aria-describedby": invalidField === field.name ? "question-form-error" : undefined, disabled, maxLength: field.maxLength ?? 4_000, value: fieldValue, onChange: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => { setAnswers((current) => ({ ...current, [field.name]: event.target.value })); if (invalidField === field.name && event.target.value.trim()) { setInvalidField(null); setFormError(null); } } };
        return <label className="question-field" key={field.name}><span className="question-field-label">{field.label}{field.required ? <em aria-hidden="true">必填</em> : null}</span>
          {isSingleSelect(field) ? <select {...controlProps}><option value="">请选择</option>{(field.options ?? []).slice(0, 3).map((option) => <option value={option} key={option}>{option}</option>)}{field.allowCustom ? <option value="__OTHER__">其他</option> : null}</select> : field.name.toLowerCase().includes("reason") ? <textarea {...controlProps} rows={3} placeholder="请说明具体情况" /> : <input {...controlProps} placeholder="请填写" />}
          {otherSelected ? <input aria-label={`${field.label}自定义内容`} autoFocus disabled={disabled} aria-invalid={invalidField === field.name} aria-required={field.required} aria-describedby={invalidField === field.name ? "question-form-error" : undefined} maxLength={field.maxLength ?? 4_000} value={customValues[field.name] ?? ""} onChange={(event) => { setCustomValues((current) => ({ ...current, [field.name]: event.target.value })); if (invalidField === field.name && event.target.value.trim()) { setInvalidField(null); setFormError(null); } }} placeholder="请补充具体内容" /> : null}
        </label>;
      })}</div>
    {formError ? <p id="question-form-error" className="question-form-error" role="alert"><CircleAlert aria-hidden="true" />{formError}</p> : null}
    <div className="actions question-actions"><button type="submit" disabled={disabled}><span className="question-action-icon"><Check aria-hidden="true" /></span>{disabled ? "处理中…" : value.submitLabel ?? "继续"}</button><button className="secondary" type="button" disabled={disabled} onClick={onCancel}><span className="question-action-icon question-action-icon-secondary"><X aria-hidden="true" /></span>{value.cancelLabel ?? "结束本次问题"}</button><span className="question-key-help">Enter 提交 · Shift + Enter 换行 · Esc 结束</span></div>
  </form>;
}

function QuestionModal({ value, disabled, onSubmit, onCancel }: { value: QuestionCardState; disabled: boolean; onSubmit: (answers: Record<string, string>) => void; onCancel: () => void }) {
  const modalRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef(onCancel);
  const disabledRef = useRef(disabled);
  cancelRef.current = onCancel;
  disabledRef.current = disabled;
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    const backgroundNodes = Array.from(document.querySelectorAll<HTMLElement>(".app-topbar, .thread-layout > .thread-sidebar, .thread-layout > .thread-main, .thread-layout > .item-inspector"));
    const wasInert = new Map(backgroundNodes.map((node) => [node, node.hasAttribute("inert")]));
    const focusables = () => Array.from(modalRef.current?.querySelectorAll<HTMLElement>("button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])") ?? []);
    const firstField = () => modalRef.current?.querySelector<HTMLElement>(".question-card input:not([disabled]), .question-card select:not([disabled]), .question-card textarea:not([disabled])") ?? null;
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        if (!event.defaultPrevented && !disabledRef.current) { event.preventDefault(); cancelRef.current(); }
        return;
      }
      if (event.key !== "Tab") return;
      const elements = focusables();
      if (elements.length === 0) return;
      const first = elements[0];
      const last = elements.at(-1) as HTMLElement;
      if (!modalRef.current?.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const onFocusIn = (event: globalThis.FocusEvent) => {
      if (modalRef.current && !modalRef.current.contains(event.target as Node)) {
        event.preventDefault();
        (firstField() ?? focusables()[0] ?? modalRef.current)?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("focusin", onFocusIn);
    document.body.style.overflow = "hidden";
    backgroundNodes.forEach((node) => node.setAttribute("inert", ""));
    (firstField() ?? focusables()[0] ?? modalRef.current)?.focus();
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("focusin", onFocusIn);
      document.body.style.overflow = previousOverflow;
      backgroundNodes.forEach((node) => { if (!wasInert.get(node)) node.removeAttribute("inert"); });
      previousFocus?.focus({ preventScroll: true });
    };
  }, [value.questionId, value.version]);
  return <div className="question-modal-layer">
    <button className="question-modal-backdrop" type="button" aria-label="关闭并结束当前操作" onClick={() => { if (!disabled) onCancel(); }} />
    <div ref={modalRef} className="question-modal" role="dialog" aria-modal="true" aria-labelledby="question-dialog-title" tabIndex={-1}>
      <QuestionCard key={`${value.runId}:${value.questionId}`} value={value} disabled={disabled} onSubmit={onSubmit} onCancel={onCancel} />
      <button className="secondary compact-icon question-modal-close" type="button" aria-label="关闭并结束当前操作" disabled={disabled} onClick={onCancel}><X aria-hidden="true" /></button>
    </div>
  </div>;
}

function workflowActionLabel(actionType: string) {
  return ({
    REFUND: "申请退款",
    EXPEDITE: "催发货",
    DELETE_ORDER: "删除订单记录",
    HIDE_ORDER: "隐藏订单",
    RESTORE_ORDER: "恢复订单"
  } as Record<string, string>)[actionType] ?? actionType;
}

function WorkflowCheckpointCard({ value, disabled, onDecision }: { value: WorkflowCheckpointState; disabled: boolean; onDecision: (decision: "APPROVE" | "REJECT") => void }) {
  const fingerprint = value.factsFingerprint.length > 12 ? `${value.factsFingerprint.slice(0, 12)}…` : value.factsFingerprint;
  return <div className="decision-card workflow-checkpoint-card" role="group" aria-label="Workflow 执行确认">
    <div className="decision-heading"><span className="checkpoint-heading-icon"><ShieldCheck aria-hidden="true" /></span><div className="question-heading-copy"><span className="checkpoint-status">确认执行</span><h2 id="workflow-checkpoint-title">请确认这项订单操作</h2><div className="question-meta"><span>固定流程 · {value.nodeId}</span><span>确认点 v{value.version}</span></div></div></div>
    <div className="checkpoint-brief">
      <dl>
        <div><dt>执行动作</dt><dd>{workflowActionLabel(value.actionType)}</dd></div>
        <div><dt>作用对象</dt><dd>{value.orderId}</dd></div>
        <div><dt>影响范围</dt><dd>{value.impactSummary || "将更新该订单的业务状态。"}</dd></div>
      </dl>
      <p className="checkpoint-fingerprint">事实已锁定 · {fingerprint}</p>
    </div>
    <p className="checkpoint-help">确认后才会向外部订单系统提交写操作；拒绝不会产生外部副作用。</p>
    <div className="actions checkpoint-actions"><button type="button" disabled={disabled} onClick={() => onDecision("APPROVE")}><span className="question-action-icon"><Check aria-hidden="true" /></span>{disabled ? "处理中…" : "确认并执行"}</button><button className="secondary" type="button" disabled={disabled} onClick={() => onDecision("REJECT")}><span className="question-action-icon question-action-icon-secondary"><X aria-hidden="true" /></span>拒绝执行</button><span className="question-key-help">Enter 确认 · Esc 拒绝</span></div>
  </div>;
}

function WorkflowCheckpointModal({ value, disabled, onDecision }: { value: WorkflowCheckpointState; disabled: boolean; onDecision: (decision: "APPROVE" | "REJECT") => void }) {
  const modalRef = useRef<HTMLDivElement>(null);
  const decisionRef = useRef(onDecision);
  const disabledRef = useRef(disabled);
  decisionRef.current = onDecision;
  disabledRef.current = disabled;
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    const backgroundNodes = Array.from(document.querySelectorAll<HTMLElement>(".app-topbar, .thread-layout > .thread-sidebar, .thread-layout > .thread-main, .thread-layout > .item-inspector"));
    const wasInert = new Map(backgroundNodes.map((node) => [node, node.hasAttribute("inert")]));
    const focusables = () => Array.from(modalRef.current?.querySelectorAll<HTMLElement>("button:not([disabled]), [tabindex]:not([tabindex='-1'])") ?? []);
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        if (!event.defaultPrevented && !disabledRef.current) { event.preventDefault(); decisionRef.current("REJECT"); }
        return;
      }
      if (event.key !== "Tab") return;
      const elements = focusables();
      if (elements.length === 0) return;
      const first = elements[0];
      const last = elements.at(-1) as HTMLElement;
      if (!modalRef.current?.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const onFocusIn = (event: globalThis.FocusEvent) => {
      if (modalRef.current && !modalRef.current.contains(event.target as Node)) {
        event.preventDefault();
        (focusables()[0] ?? modalRef.current)?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("focusin", onFocusIn);
    document.body.style.overflow = "hidden";
    backgroundNodes.forEach((node) => node.setAttribute("inert", ""));
    (focusables()[0] ?? modalRef.current)?.focus();
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("focusin", onFocusIn);
      document.body.style.overflow = previousOverflow;
      backgroundNodes.forEach((node) => { if (!wasInert.get(node)) node.removeAttribute("inert"); });
      previousFocus?.focus({ preventScroll: true });
    };
  }, [value.checkpointId, value.version]);
  return <div className="question-modal-layer checkpoint-modal-layer">
    <button className="question-modal-backdrop" type="button" aria-label="拒绝执行并关闭确认面板" onClick={() => { if (!disabled) onDecision("REJECT"); }} />
    <div ref={modalRef} className="question-modal checkpoint-modal" role="dialog" aria-modal="true" aria-labelledby="workflow-checkpoint-title" tabIndex={-1}>
      <WorkflowCheckpointCard value={value} disabled={disabled} onDecision={onDecision} />
      <button className="secondary compact-icon question-modal-close" type="button" aria-label="拒绝执行" disabled={disabled} onClick={() => onDecision("REJECT")}><X aria-hidden="true" /></button>
    </div>
  </div>;
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

function connectionMessage(error: string) {
  if (/网络连接暂时不可用|服务不可用/i.test(error)) return "网络连接暂时不可用，请检查网络后重试。";
  if (/HTTP\s+\d{3}/i.test(error)) return "订单服务没有响应，工作区暂时无法加载。";
  if (/网络|连接|超时|不可用/i.test(error)) return "暂时无法连接到订单服务。";
  return "工作区暂时无法加载。";
}

function isConnectionError(error: string) {
  return /HTTP\s+\d{3}|网络|连接|超时|不可用|实时事件/i.test(error);
}

function ConnectionRecovery({ error, loading, onRetry }: { error: string; loading: boolean; onRetry: () => void }) {
  return <section className="connection-recovery" role="status" aria-labelledby="connection-recovery-title" aria-live="polite" aria-atomic="true">
    <span className="connection-recovery-icon" aria-hidden="true"><RefreshCw /></span>
    <div className="connection-recovery-copy">
      <span className="connection-recovery-label">连接状态</span>
      <h2 id="connection-recovery-title">无法连接到订单服务</h2>
      <p>{connectionMessage(error)}</p>
      <p className="connection-recovery-hint">确认本地后端已启动后，再重新连接即可恢复工作区。</p>
      <details className="connection-diagnostics">
        <summary>查看技术信息</summary>
        <code>{error}</code>
      </details>
      <button type="button" className="icon-button connection-retry" disabled={loading} aria-busy={loading} onClick={onRetry}><RefreshCw aria-hidden="true" />{loading ? "连接中…" : "重新连接"}</button>
    </div>
  </section>;
}

function ConnectionNotice({ error, onRetry }: { error: string; onRetry: () => void }) {
  const connectionError = isConnectionError(error);
  return <div className="workspace-alert" role="alert">
    <CircleAlert aria-hidden="true" />
    <div><strong>{connectionError ? "连接暂时中断" : "当前操作无法执行"}</strong><span>{connectionError ? connectionMessage(error) : error}</span></div>
    {connectionError ? <button type="button" className="secondary compact-action" onClick={onRetry}>重新连接</button> : null}
  </div>;
}

function WorkspaceHelp() {
  return <details className="workspace-help">
    <summary><CircleHelp aria-hidden="true" />工作台提示</summary>
    <div className="workspace-help-content">
      <p>输入订单号、物流问题或售后诉求开始。订单事实会显示在卡片中；需要写入外部系统时，页面会先请求你的确认。</p>
      <p><strong>记录结构</strong>：一次请求是一条 Turn；Turn 由可恢复的 Item 组成。退款等写操作会进入受控 Workflow，并在执行前请求确认。</p>
      <div className="shortcut-list" aria-label="键盘快捷键"><span><kbd>Ctrl/⌘ K</kbd> 搜索对话</span><span><kbd>Ctrl/⌘ Shift N</kbd> 新建对话</span><span><kbd>Ctrl/⌘ Shift D</kbd> 查看最近详情</span></div>
    </div>
  </details>;
}

function duration(turn: ThreadViewTurn) {
  if (!turn.finishedAt) return null;
  const ms = Math.max(0, new Date(turn.finishedAt).valueOf() - new Date(turn.startedAt).valueOf());
  return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)}s`;
}

function ActionFallbackReceipt({ turn, disabled, pendingAction, retryingRunId, onRetry, onAction }: { turn: ThreadViewTurn; disabled: boolean; pendingAction: PendingOrderAction | null; retryingRunId: string | null; onRetry: (runId: string) => void; onAction: (sourceTurnId: string, orderId: string, actionType: OrderActionType) => void }) {
  const action = findOrderAction(turn);
  const pending = pendingAction?.sourceTurnId === turn.turnId ? { ...pendingAction, turnId: "__pending__" } : null;
  const view = action ? projectOrderAction(turn, action) : pending ? projectOrderAction(turn, pending) : null;
  if (!view) return null;
  return <div className="action-fallback-receipt"><OrderActionStatus view={view} disabled={disabled} retrying={retryingRunId === view.runId} onRetry={onRetry} onRefresh={(orderId) => onAction(turn.turnId, orderId, "REFRESH_ORDER")} /></div>;
}

function WorkflowState({ turn }: { turn: ThreadViewTurn }) {
  if (turn.workflowSteps.length === 0) return null;
  const latestStep = turn.workflowSteps.at(-1);
  if (!latestStep) return null;
  const stageLabel = ({
    RESOLVE_ORDER: "定位订单",
    VERIFY_FACTS: "核验订单事实",
    SWITCH_REQUIREMENTS: "确认执行条件",
    AUTHORIZE: "确认执行授权",
    EXECUTE_ACTION: "提交订单操作",
    VERIFY_OUTCOME: "核对操作结果",
    HANDOFF_AGENT: "整理处理结果"
  } as Record<string, string>)[latestStep.node] ?? "处理订单";
  const state = latestStep.status === "ERROR" || latestStep.status === "FAILED" ? "error" : latestStep.status === "WAITING" ? "waiting" : latestStep.status === "COMPLETED" || latestStep.status === "DONE" ? "done" : "active";
  const stateLabel = state === "error" ? "需要处理" : state === "waiting" ? "等待确认" : state === "done" ? "已完成" : "进行中";
  const summary = state === "error" ? `处理在“${stageLabel}”时遇到问题` : state === "waiting" ? `正在等待你确认${stageLabel}` : state === "done" ? `已完成${stageLabel}` : `正在${stageLabel}`;
  return <section className={`workflow-state workflow-state-${state}`} aria-label="订单处理阶段">
    <div className="workflow-state-heading"><strong>订单处理阶段</strong><span>{stateLabel}</span></div>
    <div className="workflow-state-summary" role="status"><span className="workflow-state-dot" aria-hidden="true" /><div><strong>{summary}</strong><span>完整节点、耗时和受控数据已收录在运行详情</span></div></div>
  </section>;
}

function ContinuationNotice({ turn }: { turn: ThreadViewTurn }) {
  const stopLimit = turn.decisions.find((decision) => decision.decision === "STOP_LIMIT");
  const fallback = turn.decisions.find((decision) => decision.decision === "FALLBACK");
  if (!turn.continuation && !stopLimit && !fallback) return null;
  if (stopLimit) return <p className="continuation-notice continuation-notice-warning" role="status"><Clock3 aria-hidden="true" />已达到自动决策上限，业务结果保持不变；可以继续提问或人工处理。</p>;
  if (fallback) return <p className="continuation-notice continuation-notice-warning" role="status"><CircleAlert aria-hidden="true" />Agent 已降级为可控结果，业务状态未被覆盖。</p>;
  return <p className="continuation-notice" role="status"><GitBranch aria-hidden="true" />已接续第 {turn.continuation?.cycleNo} 轮 Agent 判断，业务事实仍以本卡片为准。</p>;
}

function AgentConclusion({ value }: { value: string }) {
  const lines = value.split(/\n+/).map((line) => line.trim()).filter(Boolean);
  const unique = lines.filter((line, index) => lines.indexOf(line) === index);
  if (unique.length === 0) return null;
  return <section className="agent-conclusion" aria-label="Agent 结论"><span className="eyebrow">AGENT CONCLUSION</span><RestrictedMarkdown value={unique.join("\n\n")} className="agent-content" /></section>;
}

function LegacyQuestionCard({ value }: { value: QuestionCardState }) {
  return <section className="legacy-question-card" aria-label="历史问题卡片">
    <div className="legacy-question-heading"><span className="eyebrow">历史问题卡片 · 仅展示</span><strong>{value.title}</strong></div>
    <RestrictedMarkdown value={value.prompt} className="legacy-question-prompt" />
    {value.summary && value.summary.length > 0 ? <dl className="legacy-question-summary">{value.summary.map((line) => <div key={`${line.label}-${line.value}`}><dt>{line.label}</dt><dd>{line.value}</dd></div>)}</dl> : null}
    {value.fields.length > 0 ? <ul className="legacy-question-fields">{value.fields.map((field) => <li key={field.name}>{field.label}</li>)}</ul> : null}
    <p className="legacy-question-note">该问题来自旧版 Workflow，仅保留历史记录，不能在此重新提交。</p>
  </section>;
}

const Turn = memo(function Turn({ turn, busy, pendingAction, retryingRunId, onRetry, onRetryTurn, onInspect, onAction }: { turn: ThreadViewTurn; busy: boolean; pendingAction: PendingOrderAction | null; retryingRunId: string | null; onRetry: (runId: string) => void; onRetryTurn: (turnId: string) => void; onInspect: (turnId: string) => void; onAction: (sourceTurnId: string, orderId: string, actionType: OrderActionType) => void }) {
  const hasStructuredFacts = turn.orderCards.length > 0 || turn.logisticsTimelines.length > 0;
  return <article className={`conversation-turn thread-turn turn-${turn.status.toLowerCase()}`}>
    <div className="turn-request"><span className="turn-avatar user-avatar">你</span><div><div className="turn-meta"><strong>你的请求</strong><time>{time(turn.startedAt)}</time><span className={`turn-status status-${turn.status.toLowerCase()}`}>{statusLabel(turn.status)}</span></div><p>{turn.userMessage}</p></div></div>
    <div className="turn-response"><span className="turn-avatar agent-avatar"><Bot aria-label="Agent" /></span><div className="turn-response-body"><div className="turn-meta turn-response-meta"><strong>售后助手</strong><span className="turn-route">业务流</span><button className="detail-trigger" type="button" onClick={() => onInspect(turn.turnId)}><PanelRight aria-hidden="true" />运行详情 <span>{turn.items.length}</span></button></div>
      <div className={`turn-summary summary-${turn.activities.at(-1)?.status?.toLowerCase() ?? "active"}`}><span className="summary-icon">{activityIcon(turn.activities.at(-1)?.status ?? "ACTIVE")}</span><strong>{turnSummary(turn)}</strong>{duration(turn) ? <time>{duration(turn)}</time> : null}</div>
      {turn.continuationWarning ? <p className="turn-warning" role="status"><CircleAlert aria-hidden="true" />{turn.continuationWarning}</p> : null}
      <ContinuationNotice turn={turn} />
      <WorkflowState turn={turn} />
      {turn.legacyQuestion ? <LegacyQuestionCard value={turn.legacyQuestion} /> : null}
      {hasStructuredFacts ? <><OrderResults turn={turn} disabled={busy} pendingAction={pendingAction} retryingRunId={retryingRunId} onRetry={onRetry} onAction={onAction} />{turn.content ? <AgentConclusion value={turn.content} /> : null}</> : turn.content ? <RestrictedMarkdown value={turn.content} className="agent-content" /> : turn.status === "ACTIVE" || turn.status === "QUEUED" ? <p className="agent-content loading-copy">正在分析你的请求…</p> : null}
      {!hasStructuredFacts ? <ActionFallbackReceipt turn={turn} disabled={busy} pendingAction={pendingAction} retryingRunId={retryingRunId} onRetry={onRetry} onAction={onAction} /> : null}
      {turn.error ? <><p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{turn.error}</p>{turn.errorCode === "AGENT_DECISION_MISSING" ? <button className="secondary retry-agent-button" type="button" disabled={busy} onClick={() => onRetryTurn(turn.turnId)}>再次尝试</button> : null}</> : null}
    </div></div>
  </article>;
});

const ITEM_LABELS: Record<string, string> = { USER_MESSAGE: "用户请求", TURN_STATE: "Turn 状态", ASSISTANT_MESSAGE: "助手回复", TOOL_CALL: "工具调用", TOOL_RESULT: "工具结果", WORKFLOW_STARTED: "Workflow 启动", QUESTION_CARD: "问题卡片", QUESTION_ANSWER: "问题回答", WORKFLOW_CHECKPOINT: "执行确认", WORKFLOW_DECISION: "执行决定", WORKFLOW_QUESTION: "历史问题卡片", WORKFLOW_ANSWER: "历史问题回答", WORKFLOW_RESULT: "Workflow 回执", EXTERNAL_ACTION_STATUS: "外部动作", ORDER_LIST: "订单列表", ORDER_DETAIL: "订单详情", LOGISTICS_TIMELINE: "物流时间线", ORDER_ACTION_REQUEST: "订单动作", WORKFLOW_STEP: "Workflow 节点", AGENT_CONTINUATION: "Agent 续跑", AGENT_DECISION: "Agent 决策", EXECUTION_EVENT: "执行记录", ERROR: "错误" };
const INSPECTOR_PAGE_SIZE = 80;

function safeJsonPreview(item: AgentItem) {
  const redact = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(redact);
    if (!value || typeof value !== "object") return value;
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, nested]) => /token|secret|password|authorization|apiKey|userId/i.test(key) ? [key, "[已隐藏]"] : [key, redact(nested)]));
  };
  if (typeof item.payload.data === "string") return item.payload.data;
  try { return JSON.stringify(redact(item.payload.data), null, 2); } catch { return "无法展示该 Item"; }
}

const ItemRow = memo(function ItemRow({ item }: { item: AgentItem }) {
  return <li className={`item-row item-${item.type.toLowerCase()}`}><div className="item-row-meta"><span className="item-sequence-no">#{String(item.sequence).padStart(3, "0")}</span><strong>{ITEM_LABELS[item.type] ?? item.type}</strong><time>{dateTime(item.createdAt)}</time></div><pre>{safeJsonPreview(item)}</pre></li>;
});

const ItemInspector = memo(function ItemInspector({ turn, replayStatus, onClose }: { turn: ThreadViewTurn | null; replayStatus: "idle" | "loading" | "loaded" | "failed"; onClose: () => void }) {
  const [isDrawer, setIsDrawer] = useState(() => typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia("(max-width: 1179px)").matches);
  const [visibleCount, setVisibleCount] = useState(INSPECTOR_PAGE_SIZE);
  const inspectorRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    setVisibleCount(INSPECTOR_PAGE_SIZE);
  }, [turn?.turnId]);
  useEffect(() => {
    const media = typeof window !== "undefined" && typeof window.matchMedia === "function" ? window.matchMedia("(max-width: 1179px)") : null;
    const update = () => setIsDrawer(media?.matches ?? false);
    if (!media) return;
    update();
    media?.addEventListener?.("change", update);
    return () => media?.removeEventListener?.("change", update);
  }, []);
  useEffect(() => {
    if (!turn) return;
    const previousOverflow = document.body.style.overflow;
    const previousFocus = document.activeElement as HTMLElement | null;
    const backgroundNodes = isDrawer
      ? Array.from(document.querySelectorAll<HTMLElement>(".app-topbar, .thread-layout > .thread-sidebar, .thread-layout > .thread-main"))
      : [];
    const wasInert = new Map(backgroundNodes.map((node) => [node, node.hasAttribute("inert")]));
    const focusables = () => Array.from(inspectorRef.current?.querySelectorAll<HTMLElement>("button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])") ?? []);
    const onModalKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); onClose(); return; }
      if (!isDrawer || event.key !== "Tab") return;
      const elements = focusables();
      if (elements.length === 0) return;
      const first = elements[0];
      const last = elements.at(-1) as HTMLElement;
      if (!inspectorRef.current?.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const onFocusIn = (event: globalThis.FocusEvent) => {
      if (isDrawer && inspectorRef.current && !inspectorRef.current.contains(event.target as Node)) {
        event.preventDefault();
        closeRef.current?.focus();
      }
    };
    document.addEventListener("keydown", onModalKeyDown);
    if (isDrawer) {
      document.body.style.overflow = "hidden";
      backgroundNodes.forEach((node) => node.setAttribute("inert", ""));
      document.addEventListener("focusin", onFocusIn);
    }
    closeRef.current?.focus();
    return () => {
      document.removeEventListener("keydown", onModalKeyDown);
      document.removeEventListener("focusin", onFocusIn);
      document.body.style.overflow = previousOverflow;
      backgroundNodes.forEach((node) => { if (!wasInert.get(node)) node.removeAttribute("inert"); });
      previousFocus?.focus({ preventScroll: true });
    };
  }, [isDrawer, onClose, turn]);
  if (!turn) return null;
  const hiddenItemCount = Math.max(0, turn.items.length - visibleCount);
  const visibleItems = hiddenItemCount > 0 ? turn.items.slice(hiddenItemCount) : turn.items;
  return <><button className="inspector-backdrop" type="button" aria-label="关闭运行详情" onClick={onClose} /><aside ref={inspectorRef} className="item-inspector" role={isDrawer ? "dialog" : undefined} aria-modal={isDrawer ? "true" : undefined} aria-labelledby="item-inspector-title" aria-label={isDrawer ? undefined : "Item 序列检查器"}>
    <header className="inspector-heading"><div><span className="eyebrow">ITEM INSPECTOR</span><h2 id="item-inspector-title">运行详情</h2><p>{turn.items.length} 个持久化 Item · Turn {turn.turnId.slice(0, 8)}</p>{replayStatus === "loading" ? <small className="inspector-replay-note">正在加载终态回放…</small> : replayStatus === "failed" ? <small className="inspector-replay-note inspector-replay-failed">回放暂不可用，已显示当前已恢复事实。</small> : null}</div><button ref={closeRef} className="secondary compact-icon" type="button" aria-label="关闭运行详情" onClick={onClose}><X aria-hidden="true" /></button></header>
    <div className="inspector-mode"><strong>Item 序列</strong><span>{turn.items.length}</span></div>
    {hiddenItemCount > 0 ? <button className="secondary inspector-load-more" type="button" onClick={() => setVisibleCount((count) => Math.min(turn.items.length, count + INSPECTOR_PAGE_SIZE))}>加载更早的 {hiddenItemCount > INSPECTOR_PAGE_SIZE ? INSPECTOR_PAGE_SIZE : hiddenItemCount} 个 Item</button> : null}
    <ol className="item-sequence">{visibleItems.map((item) => <ItemRow key={item.itemId} item={item} />)}</ol>
  </aside></>;
});

export function ThreadWorkspace({ workspace }: Props) {
  const [message, setMessage] = useState("");
  const [editingThreadId, setEditingThreadId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState("");
  const [threadQuery, setThreadQuery] = useState("");
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [inspectedTurnId, setInspectedTurnId] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingOrderAction | null>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const threadSearchRef = useRef<HTMLInputElement>(null);
  const threadSidebarRef = useRef<HTMLElement>(null);
  const mobileSidebarCloseRef = useRef<HTMLButtonElement>(null);
  const currentThread = workspace.threads.find((item) => item.threadId === workspace.threadId);
  const visibleThreads = workspace.threads;
  const normalizedThreadQuery = threadQuery.trim().toLocaleLowerCase();
  const filteredThreads = normalizedThreadQuery ? visibleThreads.filter((item) => `${item.title} ${item.contextId ?? ""}`.toLocaleLowerCase().includes(normalizedThreadQuery)) : visibleThreads;
  const interaction = workspace.interaction;
  const question = interaction?.type === "QUESTION_CARD" ? interaction.question : null;
  const checkpoint = interaction?.type === "WORKFLOW_CHECKPOINT" ? interaction.checkpoint : null;
  const inputDisabled = workspace.busy || Boolean(interaction) || !workspace.threadId;
  const blockingConnection = Boolean(workspace.error && !workspace.threadId && workspace.turns.length === 0);
  const contextTitle = currentThread?.title ?? (blockingConnection ? "等待连接" : "加载中…");
  const contextStatus = currentThread ? "进行中" : blockingConnection ? "未连接" : "加载中";
  const contextStatusClass = currentThread?.status?.toLowerCase() ?? (blockingConnection ? "failed" : "active");
  const inspectedTurn = workspace.turns.find((turn) => turn.turnId === inspectedTurnId) ?? null;
  useEffect(() => {
    if (!workspace.busy) setPendingAction(null);
  }, [workspace.busy]);
  useEffect(() => {
    if (!mobileSidebarOpen) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    const sidebar = threadSidebarRef.current;
    const backgroundNodes = Array.from(document.querySelectorAll<HTMLElement>(".app-topbar, .thread-layout > .thread-main, .thread-layout > .item-inspector"));
    const wasInert = new Map(backgroundNodes.map((node) => [node, node.hasAttribute("inert")]));
    const focusables = () => Array.from(sidebar?.querySelectorAll<HTMLElement>("button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])") ?? []);
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); setMobileSidebarOpen(false); return; }
      if (event.key !== "Tab") return;
      const elements = focusables();
      if (elements.length === 0) return;
      const first = elements[0];
      const last = elements.at(-1) as HTMLElement;
      if (!sidebar?.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const onFocusIn = (event: globalThis.FocusEvent) => {
      if (sidebar && !sidebar.contains(event.target as Node)) {
        event.preventDefault();
        (mobileSidebarCloseRef.current ?? focusables()[0])?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("focusin", onFocusIn);
    document.body.style.overflow = "hidden";
    backgroundNodes.forEach((node) => node.setAttribute("inert", ""));
    (mobileSidebarCloseRef.current ?? focusables()[0])?.focus();
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("focusin", onFocusIn);
      document.body.style.overflow = previousOverflow;
      backgroundNodes.forEach((node) => { if (!wasInert.get(node)) node.removeAttribute("inert"); });
      previousFocus?.focus({ preventScroll: true });
    };
  }, [mobileSidebarOpen]);
  const inspect = useCallback((turnId: string) => { setInspectedTurnId(turnId); void workspace.loadExecution(turnId); }, [workspace.loadExecution]);
  const executeOrderAction = useCallback((sourceTurnId: string, orderId: string, actionType: OrderActionType) => {
    setPendingAction({ sourceTurnId, orderId, actionType });
    void workspace.orderAction(sourceTurnId, orderId, actionType);
  }, [workspace.orderAction]);
  const submit = (event: FormEvent) => { event.preventDefault(); const next = message.trim(); if (!next) return; void workspace.send(next); setMessage(""); };
  const keyboard = (event: KeyboardEvent<HTMLTextAreaElement>) => { if (event.nativeEvent.isComposing) return; if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } };
  const chooseThread = (nextThreadId: string) => { setEditingThreadId(null); setMobileSidebarOpen(false); setInspectedTurnId(null); workspace.selectThread(nextThreadId); };
  const startRename = (item: { threadId: string; title: string }) => { setEditingThreadId(item.threadId); setDraftTitle(item.title); };
  const cancelRename = () => { setEditingThreadId(null); setDraftTitle(""); };
  const saveRename = async () => { if (!editingThreadId || !draftTitle.trim()) { cancelRename(); return; } if (await workspace.rename(editingThreadId, draftTitle)) cancelRename(); };
  const create = useCallback(async () => { setMobileSidebarOpen(false); await workspace.createThread(); }, [workspace.createThread]);
  const closeInspector = useCallback(() => setInspectedTurnId(null), []);
  useEffect(() => {
    const onShortcut = (event: globalThis.KeyboardEvent) => {
      if (event.isComposing || (!event.ctrlKey && !event.metaKey)) return;
      const key = event.key.toLowerCase();
      if (key === "k" && !event.altKey) {
        event.preventDefault();
        threadSearchRef.current?.focus();
      } else if (event.shiftKey && key === "n" && !workspace.busy && !interaction) {
        event.preventDefault();
        void create();
      } else if (event.shiftKey && key === "d") {
        const latestTurn = workspace.turns.at(-1);
        if (!latestTurn) return;
        event.preventDefault();
        inspect(latestTurn.turnId);
      }
    };
    window.addEventListener("keydown", onShortcut);
    return () => window.removeEventListener("keydown", onShortcut);
  }, [create, inspect, interaction, workspace.busy, workspace.turns]);
  return <section className={`thread-workspace ${inspectedTurn ? "has-inspector" : ""}`} aria-label="订单调度台">
    <div className="thread-layout">
      {mobileSidebarOpen ? <button className="thread-sidebar-backdrop" type="button" aria-label="关闭对话列表" onClick={() => setMobileSidebarOpen(false)} /> : null}
      <aside ref={threadSidebarRef} id="thread-sidebar" className={`thread-sidebar ${mobileSidebarOpen ? "mobile-open" : ""}`} role={mobileSidebarOpen ? "dialog" : undefined} aria-modal={mobileSidebarOpen ? "true" : undefined} aria-labelledby={mobileSidebarOpen ? "thread-list-title" : undefined} aria-label={mobileSidebarOpen ? undefined : "对话列表"}>
        <div className="sidebar-heading"><div><span className="eyebrow">THREADS</span><h2 id="thread-list-title">对话记录</h2></div><div className="sidebar-heading-actions"><button className="secondary icon-button new-thread-button" type="button" onClick={() => void create()} aria-label="新建对话"><ListPlus aria-hidden="true" /><span>新建</span></button><button ref={mobileSidebarCloseRef} className="secondary compact-icon mobile-sidebar-close" type="button" onClick={() => setMobileSidebarOpen(false)} aria-label="关闭对话列表"><X aria-hidden="true" /></button></div></div>
        <div className="thread-search"><Search aria-hidden="true" /><input ref={threadSearchRef} type="search" aria-label="搜索对话" value={threadQuery} onChange={(event) => setThreadQuery(event.target.value)} placeholder="搜索对话" /></div>
        <div className="thread-list">{filteredThreads.length === 0 ? <p className="thread-list-empty">{normalizedThreadQuery ? "没有匹配的对话。" : "还没有对话记录。"}</p> : null}{filteredThreads.map((item) => <div className={`thread-row ${item.threadId === workspace.threadId ? "selected" : ""}`} key={item.threadId}>
          {editingThreadId === item.threadId ? <input className="thread-row-title-input" aria-label={`重命名 ${item.title}`} autoFocus value={draftTitle} onChange={(event) => setDraftTitle(event.target.value)} onBlur={cancelRename} onKeyDown={(event) => { if (event.nativeEvent.isComposing) return; if (event.key === "Escape") { event.preventDefault(); cancelRename(); } if (event.key === "Enter") { event.preventDefault(); void saveRename(); } }} /> : <button type="button" className="thread-row-select" aria-current={item.threadId === workspace.threadId ? "page" : undefined} onClick={() => chooseThread(item.threadId)}><span><strong>{item.title}</strong><small>{item.contextId ?? "订单售后"}</small></span></button>}
          <div className="thread-row-side"><span className={`status status-${item.status.toLowerCase()}`}>{item.status === "ACTIVE" ? "进行中" : "历史"}</span><div className="thread-row-actions">{editingThreadId !== item.threadId ? <button className="thread-row-action" type="button" aria-label={`重命名对话 ${item.title}`} title={`重命名对话 ${item.title}`} onClick={() => startRename(item)}><Pencil aria-hidden="true" /></button> : null}</div></div>
        </div>)}</div>
        <div className="thread-sidebar-note"><GitBranch aria-hidden="true" /><p>订单事实、确认记录和处理结果会留在对应 Turn；对话记录按 Thread 持续保留。</p></div>
      </aside>
      <main className="thread-main">
         <div className="thread-context-bar"><div><span className="eyebrow">CURRENT THREAD</span><h2>{contextTitle}</h2><span className="thread-context-summary">{currentThread?.contextId ?? "订单售后"} · {workspace.turns.length} 个请求</span></div><div className="thread-context-actions"><button className="secondary icon-button mobile-thread-toggle" type="button" aria-expanded={mobileSidebarOpen} aria-controls="thread-sidebar" onClick={() => setMobileSidebarOpen(true)}><Menu aria-hidden="true" />对话列表</button><span className={`status status-${contextStatusClass}`}>{contextStatus}</span></div></div>
        {workspace.error && !blockingConnection ? <ConnectionNotice error={workspace.error} onRetry={workspace.retryConnection} /> : null}
        <div className="thread-records">{workspace.loading ? <div className="conversation-empty"><Bot aria-hidden="true" /><h2>正在恢复对话</h2><p>正在读取订单事实和历史结果。</p></div> : blockingConnection ? <ConnectionRecovery error={workspace.error ?? "工作区连接失败"} loading={workspace.loading} onRetry={workspace.retryConnection} /> : workspace.turns.length === 0 ? <div className="conversation-empty"><ShieldCheck aria-hidden="true" /><h2>直接输入请求</h2><p>可以直接输入订单号、物流问题或售后诉求。</p></div> : <>{workspace.turns.map((turn) => <Turn key={turn.turnId} turn={turn} busy={workspace.busy} pendingAction={pendingAction} retryingRunId={workspace.retryingRunId} onRetry={workspace.retry} onRetryTurn={workspace.retryTurn} onInspect={inspect} onAction={executeOrderAction} />)}</>}</div>
        <form className="composer" onSubmit={submit}><label htmlFor="thread-message">输入请求</label><textarea ref={composerRef} id="thread-message" value={message} disabled={inputDisabled} onKeyDown={keyboard} onChange={(event) => setMessage(event.target.value)} placeholder="输入订单号、物流问题或售后诉求…" /><div className="composer-actions"><WorkspaceHelp /><span className="composer-key-help">Enter 发送 · Shift + Enter 换行</span>{workspace.busy ? <button className="secondary icon-button" type="button" onClick={() => void workspace.cancel()}><Square aria-hidden="true" />取消处理</button> : <button className="icon-button" type="submit" disabled={!message.trim() || inputDisabled}><Send aria-hidden="true" />发送</button>}</div></form>
      </main>
      {question ? <QuestionModal value={question} disabled={workspace.busy} onSubmit={(answers) => void workspace.answer(answers)} onCancel={() => void workspace.answer({}, "CANCEL")} /> : null}
      {checkpoint ? <WorkflowCheckpointModal value={checkpoint} disabled={workspace.busy} onDecision={(decision) => void workspace.decideCheckpoint(decision)} /> : null}
      <ItemInspector turn={inspectedTurn} replayStatus={inspectedTurnId ? workspace.executionReplayStates[inspectedTurnId] ?? "idle" : "idle"} onClose={closeInspector} />
    </div>
  </section>;
}
