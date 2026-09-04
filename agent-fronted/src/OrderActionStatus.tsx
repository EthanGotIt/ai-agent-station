import { CheckCircle2, CircleAlert, Clock3, RotateCcw } from "lucide-react";
import { ACTION_LABELS, type OrderActionProjection } from "./orderActionProjection";

type Props = {
  view: OrderActionProjection;
  disabled: boolean;
  retrying: boolean;
  onRetry: (runId: string) => void;
  onRefresh: (orderId: string) => void;
};

function statusCopy(view: OrderActionProjection) {
  if (view.rejected) return "操作已结束，未执行外部动作";
  if (view.receipt?.verificationStatus === "PENDING") return "操作已受理，最新状态暂未核验";
  if (view.receipt?.verificationStatus === "VERIFIED") return view.receipt.verificationMessage ?? "最新订单状态已核验";
  if (view.state === "queued") return "已提交，正在排队";
  if (view.state === "waiting") return "需要确认，确认面板已打开";
  if (view.state === "active") {
    return "正在处理业务操作";
  }
  if (view.state === "error") {
    if (view.receipt?.attemptCount && view.receipt.maxAttempts) {
      return `自动重试 ${view.receipt.attemptCount}/${view.receipt.maxAttempts} 次后仍未完成`;
    }
    return view.error ?? "操作未完成，可查看运行详情";
  }
  if (view.request.actionType === "DELETE_ORDER" && view.deleted) return "订单记录已删除";
  return view.request.actionType === "QUERY_LOGISTICS" || view.request.actionType === "REFRESH_ORDER"
    ? "最新订单事实已更新"
    : "业务操作已完成";
}

function StatusIcon({ state }: { state: OrderActionProjection["state"] }) {
  if (state === "error") return <CircleAlert aria-hidden="true" />;
  if (state === "done") return <CheckCircle2 aria-hidden="true" />;
  return <Clock3 aria-hidden="true" />;
}

/** 订单卡片内的单一动作回执，避免把同一结果复制到全局弹窗和对话气泡。 */
export function OrderActionStatus({ view, disabled, retrying, onRetry, onRefresh }: Props) {
  const verificationPending = view.receipt?.verificationStatus === "PENDING";
  const retryable = view.state === "error" && view.retryable && view.runId;
  return <div className={`order-action-status order-action-status-${view.state}`} role="status" aria-live="polite">
    <span className="order-action-status-icon"><StatusIcon state={view.state} /></span>
    <div className="order-action-status-copy"><strong>{ACTION_LABELS[view.request.actionType]}</strong><span>{statusCopy(view)}</span></div>
    {verificationPending ? <button className="secondary compact-action" type="button" disabled={disabled} onClick={() => onRefresh(view.request.orderId)}>重新查询</button> : null}
    {retryable ? <button className="secondary compact-action" type="button" disabled={disabled || retrying} onClick={() => onRetry(view.runId as string)}><RotateCcw aria-hidden="true" />{retrying ? "重试中…" : "人工重试"}</button> : null}
  </div>;
}
