import type {
  AgentItem,
  ExternalActionReceipt,
  ExternalActionStatus,
  OrderActionType,
  ThreadViewTurn
} from "./threadTypes";

export type OrderActionViewState = "queued" | "active" | "waiting" | "error" | "done";

export type OrderActionRequest = {
  sourceTurnId: string;
  orderId: string;
  actionType: OrderActionType;
  turnId: string;
};

export type OrderActionProjection = {
  request: OrderActionRequest;
  state: OrderActionViewState;
  receipt: ExternalActionReceipt | null;
  runId: string | null;
  error: string | null;
  rejected: boolean;
  retryable: boolean;
};

export const ACTION_LABELS: Record<OrderActionType, string> = {
  QUERY_LOGISTICS: "查物流",
  REFRESH_ORDER: "刷新订单",
  REFUND: "申请退款",
  EXPEDITE: "催发货",
  HIDE_ORDER: "隐藏记录",
  RESTORE_ORDER: "恢复记录"
};

export function isOrderActionType(value: unknown): value is OrderActionType {
  return typeof value === "string" && Object.prototype.hasOwnProperty.call(ACTION_LABELS, value);
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? value as Record<string, unknown> : null;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function externalStatus(value: unknown): value is ExternalActionStatus {
  return typeof value === "string" && ["PENDING", "PROCESSING", "RETRY_WAIT", "MANUAL_RETRY_REQUIRED", "SUCCEEDED"].includes(value);
}

function externalReceipt(data: Record<string, unknown>): ExternalActionReceipt {
  return {
    actionType: stringValue(data.actionType),
    orderId: stringValue(data.orderId),
    code: stringValue(data.code),
    message: stringValue(data.message),
    attemptCount: numberValue(data.attemptCount),
    retryCycleAttemptCount: numberValue(data.retryCycleAttemptCount),
    maxAttempts: numberValue(data.maxAttempts),
    nextAttemptAt: stringValue(data.nextAttemptAt),
    verificationStatus: stringValue(data.verificationStatus),
    verificationMessage: stringValue(data.verificationMessage),
    verifiedAt: stringValue(data.verifiedAt)
  };
}

/** 从已折叠到来源 Turn 的 Item 中定位同订单最近一次确定性动作。 */
export function findOrderAction(turn: ThreadViewTurn, orderId?: string): OrderActionRequest | null {
  for (const item of [...turn.items].reverse()) {
    if (item.type !== "ORDER_ACTION_REQUEST") continue;
    const data = recordValue(item.payload.data);
    const sourceTurnId = stringValue(data?.sourceTurnId);
    const actionOrderId = stringValue(data?.orderId);
    const actionType = data?.actionType;
    if (!sourceTurnId || !actionOrderId || !isOrderActionType(actionType)) continue;
    if (orderId && actionOrderId !== orderId) continue;
    return { sourceTurnId, orderId: actionOrderId, actionType, turnId: item.turnId ?? turn.turnId };
  }
  // 历史 Workflow 可能只有 EXTERNAL_ACTION_STATUS，没有 ORDER_ACTION_REQUEST；保留其人工重试入口。
  if (turn.externalActionStatus === "MANUAL_RETRY_REQUIRED" && turn.workflowRunId) {
    const receiptOrderId = turn.externalActionReceipt?.orderId;
    if (!orderId || !receiptOrderId || receiptOrderId === orderId) {
      return {
        sourceTurnId: turn.turnId,
        orderId: receiptOrderId ?? orderId ?? "当前订单",
        actionType: isOrderActionType(turn.externalActionReceipt?.actionType) ? turn.externalActionReceipt.actionType : "REFUND",
        turnId: turn.turnId
      };
    }
  }
  return null;
}

function itemData(item: AgentItem) {
  return recordValue(item.payload.data);
}

function stateFromTurnStatus(status: unknown): OrderActionViewState | null {
  if (status === "QUEUED") return "queued";
  if (status === "ACTIVE" || status === "WAITING_EXTERNAL_ACTION") return "active";
  if (status === "WAITING_USER_INPUT") return "waiting";
  if (status === "FAILED" || status === "TIMED_OUT" || status === "CANCELLED") return "error";
  if (status === "COMPLETED") return "done";
  return null;
}

/** 将一个动作 Turn 的技术 Item 投影为订单卡片可读的状态，不引入第二份全局运行状态。 */
export function projectOrderAction(turn: ThreadViewTurn, request: OrderActionRequest): OrderActionProjection {
  const actionItems = turn.items.filter((item) => item.turnId === request.turnId);
  let state: OrderActionViewState = "queued";
  let receipt: ExternalActionReceipt | null = null;
  let runId: string | null = null;
  let error: string | null = null;
  let rejected = false;
  let retryable = false;
  let hasBusinessFact = false;
  for (const item of actionItems) {
    const data = itemData(item);
    if (item.type === "TURN_STATE") {
      const next = stateFromTurnStatus(data?.status);
      if (next) state = next;
    } else if (item.type === "WORKFLOW_QUESTION") {
      state = "waiting";
      runId = stringValue(data?.runId) ?? runId;
    } else if (item.type === "WORKFLOW_RESULT") {
      rejected = data?.status === "REJECTED";
      state = rejected ? "done" : "done";
      runId = stringValue(data?.runId) ?? runId;
    } else if (item.type === "EXTERNAL_ACTION_STATUS" && data && externalStatus(data.status)) {
      state = data.status === "SUCCEEDED" ? "done" : data.status === "MANUAL_RETRY_REQUIRED" ? "error" : "active";
      retryable = data.status === "MANUAL_RETRY_REQUIRED";
      runId = stringValue(data.runId) ?? runId;
      receipt = { ...(receipt ?? {}), ...externalReceipt(data) };
    } else if (item.type === "ERROR") {
      state = "error";
      error = typeof item.payload.data === "string" ? item.payload.data : "订单动作未完成";
    } else if (["ORDER_LIST", "ORDER_DETAIL", "LOGISTICS_TIMELINE"].includes(item.type)) {
      hasBusinessFact = true;
    }
  }
  if (hasBusinessFact && (request.actionType === "QUERY_LOGISTICS" || request.actionType === "REFRESH_ORDER")) state = "done";
  return { request, state, receipt, runId, error, rejected, retryable };
}
