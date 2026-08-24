import type {
  AgentItem,
  AgentItemPayload,
  AgentItemWire,
  AgentTurnStatus,
  BusinessProgress,
  ExternalActionReceipt,
  ExternalActionStatus,
  LogisticsEvent,
  LogisticsTimeline,
  OrderCard,
  OrderActionType,
  QuestionField,
  QuestionCardState,
  QuestionSummaryLine,
  ThreadViewTurn
} from "./threadTypes";

function safeJson<T>(payload: string): T | null {
  try {
    return JSON.parse(payload) as T;
  } catch {
    return null;
  }
}

function parseQuestion(payload: AgentItemPayload): QuestionCardState | null {
  const raw = payload.kind === "WORKFLOW_QUESTION" ? payload.data : null;
  const value = raw && typeof raw === "object" ? raw as Partial<QuestionCardState> & { fields?: unknown } : null;
  if (!value?.runId || !value.questionId || !value.checkpointId) return null;
  const fields = Array.isArray(value.fields)
    ? value.fields
    : value.fields && typeof value.fields === "object" && "fields" in value.fields
      ? (value.fields as { fields?: unknown }).fields
      : [];
  const normalizedFields = Array.isArray(fields)
    ? fields.map((field, index) => normalizeQuestionField(field, index)).filter((field): field is QuestionField => field !== null)
    : [];
  const summary = Array.isArray(value.summary)
    ? value.summary.map((line) => {
      const summaryLine = recordValue(line);
      if (!summaryLine || typeof summaryLine.label !== "string" || typeof summaryLine.value !== "string") return null;
      return { label: summaryLine.label, value: summaryLine.value } satisfies QuestionSummaryLine;
    }).filter((line): line is QuestionSummaryLine => line !== null)
    : undefined;
  return {
    runId: value.runId,
    questionId: value.questionId,
    checkpointId: value.checkpointId,
    operation: typeof value.operation === "string" ? value.operation : undefined,
    step: typeof value.step === "string" ? value.step : undefined,
    stepNo: typeof value.stepNo === "number" ? value.stepNo : undefined,
    version: Number(value.version ?? 0),
    title: value.title ?? "需要确认",
    prompt: value.prompt ?? "请确认是否继续。",
    fields: normalizedFields,
    summary
  };
}

function normalizeQuestionField(raw: unknown, index: number): QuestionField | null {
  const value = recordValue(raw);
  if (!value || typeof value.name !== "string" || value.name.trim() === "") return null;
  const rawType = typeof value.type === "string" ? value.type.toUpperCase() : "TEXT";
  const type = rawType === "CONFIRM" || rawType === "SELECT" ? "SINGLE_SELECT" : rawType;
  const options = Array.isArray(value.options)
    ? value.options.filter((option): option is string => typeof option === "string" && option.trim() !== "").slice(0, 3)
    : [];
  return {
    name: value.name,
    label: typeof value.label === "string" && value.label.trim() ? value.label : `回答 ${index + 1}`,
    type,
    required: value.required !== false,
    maxLength: typeof value.maxLength === "number" && value.maxLength > 0 ? value.maxLength : 4_000,
    options,
    allowCustom: value.allowCustom === true
  };
}

function decodePayload(type: string, payload: string, schemaVersion: number): AgentItemPayload {
  const parsed = safeJson<AgentItemPayload>(payload);
  if (schemaVersion === 1 && parsed?.schemaVersion === 1 && parsed.kind) return parsed;
  return {
    schemaVersion: 1,
    kind: type,
    data: payload
  } as AgentItemPayload;
}

function normalizeItem(item: AgentItemWire | AgentItem): AgentItem {
  if (typeof item.payload === "object") return item as AgentItem;
  return {
    ...item,
    schemaVersion: 1,
    payload: decodePayload(item.type, item.payload, item.schemaVersion)
  };
}

function payloadText(payload: AgentItemPayload): string {
  return typeof payload.data === "string" ? payload.data : JSON.stringify(payload.data);
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? value as Record<string, unknown> : null;
}

function isExternalActionStatus(value: unknown): value is ExternalActionStatus {
  return typeof value === "string" && ["PENDING", "PROCESSING", "RETRY_WAIT", "MANUAL_RETRY_REQUIRED", "SUCCEEDED"].includes(value);
}

function parseExternalAction(payload: AgentItemPayload): { runId: string | null; status: ExternalActionStatus; receipt: ExternalActionReceipt } | null {
  if (payload.kind !== "EXTERNAL_ACTION_STATUS") return null;
  const data = recordValue(payload.data);
  if (!isExternalActionStatus(data?.status)) return null;
  return {
    runId: typeof data?.runId === "string" ? data.runId : null,
    status: data.status,
    receipt: {
      actionType: stringValue(data?.actionType) ?? undefined,
      orderId: stringValue(data?.orderId) ?? undefined,
      code: stringValue(data?.code) ?? undefined,
      message: stringValue(data?.message) ?? undefined,
      attemptCount: numberValue(data?.attemptCount) ?? undefined,
      retryCycleAttemptCount: numberValue(data?.retryCycleAttemptCount) ?? undefined,
      verificationStatus: stringValue(data?.verificationStatus) ?? undefined,
      verificationMessage: stringValue(data?.verificationMessage) ?? undefined,
      verifiedAt: stringValue(data?.verifiedAt) ?? undefined
    }
  };
}

function turnStatusForExternalAction(status: ExternalActionStatus): AgentTurnStatus | null {
  if (status === "RETRY_WAIT") return "WAITING_EXTERNAL_ACTION";
  if (status === "MANUAL_RETRY_REQUIRED") return "FAILED";
  if (status === "SUCCEEDED") return "COMPLETED";
  return null;
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function numberValue(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function parseOrderCard(value: unknown): OrderCard | null {
  const data = recordValue(value);
  const orderId = stringValue(data?.orderId);
  const status = stringValue(data?.orderStatus);
  if (!orderId || !status) return null;
  return {
    orderId,
    status,
    createdAt: stringValue(data?.createdAt),
    expectedDeliveryAt: stringValue(data?.expectedDeliveryAt),
    lastLogisticsAt: stringValue(data?.lastLogisticsAt),
    logisticsStatus: stringValue(data?.logisticsStatus),
    paidAmount: numberValue(data?.paidAmount),
    currency: stringValue(data?.currency),
    itemSummary: stringValue(data?.itemSummary),
    visibility: stringValue(data?.visibility) ?? "ACTIVE"
  };
}

function buildOrderCards(items: AgentItem[]): OrderCard[] {
  const orders = new Map<string, OrderCard>();
  for (const item of items) {
    if (item.type === "ORDER_LIST") {
      const data = recordValue(item.payload.data);
      const values = Array.isArray(data?.orders) ? data.orders : [];
      for (const value of values) {
        const order = parseOrderCard(value);
        if (order) orders.set(order.orderId, order);
      }
    }
    if (item.type === "ORDER_DETAIL") {
      const order = parseOrderCard(item.payload.data);
      if (order) orders.set(order.orderId, order);
    }
  }
  return [...orders.values()];
}

function parseLogisticsEvent(value: unknown): LogisticsEvent | null {
  const data = recordValue(value);
  const eventId = stringValue(data?.eventId);
  const status = stringValue(data?.status);
  const occurredAt = stringValue(data?.occurredAt);
  if (!eventId || !status || !occurredAt) return null;
  return {
    eventId,
    status,
    location: stringValue(data?.location) ?? "",
    description: stringValue(data?.description) ?? "",
    occurredAt
  };
}

function buildLogisticsTimelines(items: AgentItem[]): LogisticsTimeline[] {
  const timelines = new Map<string, LogisticsTimeline>();
  for (const item of items) {
    if (item.type !== "LOGISTICS_TIMELINE") continue;
    const data = recordValue(item.payload.data);
    const orderId = stringValue(data?.orderId);
    if (!orderId) continue;
    const events = Array.isArray(data?.events)
      ? data.events.map(parseLogisticsEvent).filter((event): event is LogisticsEvent => event !== null)
      : [];
    timelines.set(orderId, { orderId, events });
  }
  return [...timelines.values()];
}

function parseOrderAction(item: AgentItem): { sourceTurnId: string; orderId: string; actionType: OrderActionType } | null {
  if (item.type !== "ORDER_ACTION_REQUEST") return null;
  const data = recordValue(item.payload.data);
  const sourceTurnId = stringValue(data?.sourceTurnId);
  const orderId = stringValue(data?.orderId);
  const actionType = stringValue(data?.actionType);
  if (!sourceTurnId || !orderId || !actionType
    || !["QUERY_LOGISTICS", "REFRESH_ORDER", "REFUND", "EXPEDITE", "HIDE_ORDER", "RESTORE_ORDER"].includes(actionType)) {
    return null;
  }
  return { sourceTurnId, orderId, actionType: actionType as OrderActionType };
}

function workflowRunFromItem(item: AgentItem): string | null {
  if (item.type !== "WORKFLOW_ANSWER" && item.type !== "WORKFLOW_RESULT") return null;
  const data = recordValue(item.payload.data);
  return stringValue(data?.runId);
}

function buildActivities(items: AgentItem[]): BusinessProgress[] {
  return items
    .map((item): BusinessProgress | null => {
      const data = recordValue(item.payload.data);
      if (item.type === "TURN_STATE" && typeof data?.status === "string") {
        const status = data.status;
        const state = status === "WAITING_USER_INPUT" || status === "WAITING_EXTERNAL_ACTION"
          ? "WAITING"
          : status === "COMPLETED" ? "DONE"
            : ["FAILED", "CANCELLED", "TIMED_OUT"].includes(status) ? "ERROR" : "ACTIVE";
        const label = ({
          QUEUED: "请求已排队",
          ACTIVE: "正在分析请求",
          WAITING_USER_INPUT: "等待你的确认",
          WAITING_EXTERNAL_ACTION: "正在处理业务操作",
          COMPLETED: "请求已完成",
          CANCELLED: "请求已取消",
          TIMED_OUT: "请求处理超时",
          FAILED: "请求未能完成"
        } as Record<string, string>)[status] ?? "正在处理请求";
        return { id: `${item.itemId}-state`, label, detail: null, status: state, sequence: item.sequence };
      }
      if (item.type === "EXECUTION_EVENT") {
        return { id: `${item.itemId}-context`, label: "已整理请求上下文", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "TOOL_RESULT") {
        return { id: `${item.itemId}-fact`, label: "已核对订单与物流事实", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "ORDER_LIST") {
        const orders = recordValue(item.payload.data)?.orders;
        const count = Array.isArray(orders) ? orders.length : 0;
        return { id: `${item.itemId}-orders`, label: count > 0 ? `已找到 ${count} 个匹配订单` : "没有找到匹配订单", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "ORDER_DETAIL") {
        return { id: `${item.itemId}-order`, label: "已读取订单详情", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "LOGISTICS_TIMELINE") {
        return { id: `${item.itemId}-logistics`, label: "已生成物流时间线", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_STARTED") {
        return { id: `${item.itemId}-workflow`, label: "已启动售后流程", detail: "正在核对订单条件", status: "ACTIVE", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_QUESTION") {
        const question = parseQuestion(item.payload);
        return { id: `${item.itemId}-question`, label: "需要你确认下一步", detail: question?.title ?? null, status: "WAITING", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_ANSWER") {
        return { id: `${item.itemId}-answer`, label: "已收到你的选择", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_RESULT") {
        const resultStatus = typeof data?.status === "string" ? data.status : typeof item.payload.data === "string" ? item.payload.data : "COMPLETED";
        const rejected = resultStatus === "REJECTED";
        return { id: `${item.itemId}-result`, label: rejected ? "已取消业务操作" : "售后流程已完成", detail: null, status: rejected ? "DONE" : "DONE", sequence: item.sequence };
      }
      if (item.type === "EXTERNAL_ACTION_STATUS" && typeof data?.status === "string") {
        const status = data.status;
        const entry = {
          PENDING: ["已创建业务操作", "等待执行"],
          PROCESSING: ["正在提交业务操作", null],
          RETRY_WAIT: ["外部系统暂未完成", "系统会自动重试"],
          MANUAL_RETRY_REQUIRED: ["需要人工重试业务操作", null],
          SUCCEEDED: ["业务操作已完成", null]
        } as Record<string, [string, string | null]>;
        const [label, detail] = entry[status] ?? ["正在处理业务操作", null];
        return { id: `${item.itemId}-action`, label, detail, status: status === "SUCCEEDED" ? "DONE" : status === "MANUAL_RETRY_REQUIRED" ? "ERROR" : "ACTIVE", sequence: item.sequence };
      }
      if (item.type === "ERROR") {
        return { id: `${item.itemId}-error`, label: "执行遇到问题", detail: "可以检查结果后重试", status: "ERROR", sequence: item.sequence };
      }
      return null;
    })
    .filter((entry): entry is BusinessProgress => entry !== null)
    .filter((entry, index, entries) => index === 0 || entry.label !== entries[index - 1].label || entry.status !== entries[index - 1].status);
}

function buildTurn(turnId: string, sourceItems: AgentItem[]): ThreadViewTurn {
  const orderedItems = [...sourceItems].sort((left, right) => left.sequence - right.sequence);
  const current: ThreadViewTurn = {
    turnId,
    userMessage: "",
    content: "",
    status: "ACTIVE",
    error: null,
    startedAt: orderedItems[0]?.createdAt ?? new Date(0).toISOString(),
    finishedAt: null,
    workflowRunId: null,
    externalActionStatus: null,
    externalActionReceipt: null,
    items: orderedItems,
    activities: [],
    orderCards: [],
    logisticsTimelines: [],
    question: null,
    sourceTurnId: null,
    inputKind: "MESSAGE"
  };
  for (const item of orderedItems) {
    if (item.type === "USER_MESSAGE") current.userMessage = payloadText(item.payload);
    if (item.type === "ASSISTANT_MESSAGE") current.content = `${current.content}${current.content ? "\n" : ""}${payloadText(item.payload)}`;
    if (item.type === "ORDER_ACTION_REQUEST") {
      const action = parseOrderAction(item);
      if (action) {
        current.sourceTurnId = action.sourceTurnId;
        current.inputKind = "ORDER_ACTION";
      }
    }
    if (item.type === "WORKFLOW_ANSWER") {
      current.inputKind = "WORKFLOW_ANSWER";
      // 回答子 Turn 折回来源 Turn 后，旧问题不再是当前待处理事实。
      current.question = null;
    }
    if (item.type === "ERROR") {
      current.error = payloadText(item.payload);
      current.status = "FAILED";
      current.finishedAt = item.createdAt;
    }
    if (item.type === "TURN_STATE" && item.payload.kind === "TURN_STATE") {
      const state = item.payload.data;
      if (state && typeof state === "object" && "status" in state && typeof state.status === "string") {
        const status = state.status as AgentTurnStatus;
        current.status = status;
        if (terminal(status)) current.finishedAt = item.createdAt;
      }
    }
    if (item.type === "WORKFLOW_QUESTION") {
      const question = parseQuestion(item.payload);
      if (question) {
        current.workflowRunId = question.runId;
        current.question = question;
      }
      if (!terminal(current.status)) current.status = "WAITING_USER_INPUT";
    }
    const itemRunId = workflowRunFromItem(item);
    if (itemRunId) current.workflowRunId = itemRunId;
    if (item.type === "EXTERNAL_ACTION_STATUS") {
      const action = parseExternalAction(item.payload);
      if (action) {
        current.workflowRunId = action.runId ?? current.workflowRunId;
        current.externalActionStatus = action.status;
        current.externalActionReceipt = { ...(current.externalActionReceipt ?? {}), ...action.receipt };
        const nextStatus = turnStatusForExternalAction(action.status);
        if (nextStatus && !terminal(current.status)) current.status = nextStatus;
      }
    }
    if (item.type === "WORKFLOW_RESULT" && !terminal(current.status)) current.status = "COMPLETED";
  }
  return {
    ...current,
    activities: buildActivities(orderedItems),
    orderCards: buildOrderCards(orderedItems),
    logisticsTimelines: buildLogisticsTimelines(orderedItems)
  };
}

function rebuildTurns(items: AgentItem[]): ThreadViewTurn[] {
  const grouped = new Map<string, AgentItem[]>();
  for (const item of items) {
    if (!item.turnId) continue;
    const current = grouped.get(item.turnId) ?? [];
    current.push(item);
    grouped.set(item.turnId, current);
  }
  const physical = new Map([...grouped.entries()].map(([turnId, turnItems]) => [turnId, buildTurn(turnId, turnItems)]));
  const runOwners = new Map<string, string>();
  for (const turn of physical.values()) {
    // 回答子 Turn 也会携带 runId，但它不是 Workflow 的归属 Turn；否则会覆盖来源 Turn，无法折回已结束的问题。
    if (turn.workflowRunId && turn.inputKind !== "WORKFLOW_ANSWER") runOwners.set(turn.workflowRunId, turn.turnId);
  }
  const resolveTarget = (turn: ThreadViewTurn): string | null => {
    let target = turn.sourceTurnId
      ?? (turn.inputKind === "WORKFLOW_ANSWER" && turn.workflowRunId ? runOwners.get(turn.workflowRunId) ?? null : null);
    const visited = new Set<string>();
    while (target && physical.get(target)?.sourceTurnId && !visited.has(target)) {
      visited.add(target);
      target = physical.get(target)?.sourceTurnId ?? target;
    }
    return target && target !== turn.turnId && physical.has(target) ? target : null;
  };
  const mergedItems = new Map<string, AgentItem[]>();
  for (const turn of physical.values()) mergedItems.set(turn.turnId, [...turn.items]);
  const folded = new Set<string>();
  for (const turn of physical.values()) {
    const target = resolveTarget(turn);
    if (!target) continue;
    mergedItems.get(target)?.push(...turn.items);
    folded.add(turn.turnId);
  }
  return [...mergedItems.entries()]
    .filter(([turnId]) => !folded.has(turnId))
    .map(([turnId, turnItems]) => buildTurn(turnId, turnItems))
    .sort((left, right) => left.items[0]?.sequence - right.items[0]?.sequence);
}

function terminal(status: AgentTurnStatus) {
  return ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(status);
}
export {
  buildActivities,
  buildLogisticsTimelines,
  buildOrderCards,
  buildTurn,
  decodePayload,
  normalizeItem,
  normalizeQuestionField,
  parseExternalAction,
  parseLogisticsEvent,
  parseOrderAction,
  parseQuestion,
  payloadText,
  rebuildTurns,
  recordValue,
  terminal,
  workflowRunFromItem
};
