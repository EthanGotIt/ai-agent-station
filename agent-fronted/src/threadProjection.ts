import type {
  AgentItem,
  AgentItemPayload,
  AgentItemWire,
  AgentTurnStatus,
  AgentContinuationFact,
  AgentDecisionFact,
  BusinessProgress,
  ExternalActionReceipt,
  ExternalActionStatus,
  AgentInteraction,
  LogisticsEvent,
  LogisticsTimeline,
  OrderCard,
  OrderActionType,
  QuestionAnswerFact,
  QuestionField,
  QuestionCardState,
  QuestionSummaryLine,
  ThreadViewTurn,
  WorkflowCheckpointState,
  WorkflowDecisionFact,
  WorkflowStepFact
} from "./threadTypes";

function safeJson<T>(payload: string): T | null {
  try {
    return JSON.parse(payload) as T;
  } catch {
    return null;
  }
}

function parseQuestion(payload: AgentItemPayload): QuestionCardState | null {
  if (payload.kind !== "QUESTION_CARD" && payload.kind !== "WORKFLOW_QUESTION") return null;
  const value = recordValue(payload.data);
  if (!value) return null;
  const runId = stringValue(value.runId);
  const questionId = stringValue(value.questionId);
  if (!questionId) return null;
  const legacy = payload.kind === "WORKFLOW_QUESTION";
  const fields = parseQuestionFields(value.fields ?? value.fieldsJson);
  const summary = Array.isArray(value.summary)
    ? value.summary.map((line) => {
      const summaryLine = recordValue(line);
      if (!summaryLine || typeof summaryLine.label !== "string" || typeof summaryLine.value !== "string") return null;
      return { label: summaryLine.label, value: summaryLine.value } satisfies QuestionSummaryLine;
    }).filter((line): line is QuestionSummaryLine => line !== null)
    : undefined;
  const rawResumeTarget = stringValue(value.resumeTarget);
  const resumeTarget = rawResumeTarget === "AGENT" || rawResumeTarget === "WORKFLOW"
    ? rawResumeTarget
    : "WORKFLOW";
  if ((legacy || resumeTarget === "WORKFLOW") && !runId) return null;
  return {
    kind: legacy ? "LEGACY_WORKFLOW_QUESTION" : "QUESTION_CARD",
    runId,
    questionId,
    turnId: stringValue(value.turnId),
    resumeTarget,
    operation: stringValue(value.operation) ?? undefined,
    step: stringValue(value.step) ?? undefined,
    stepNo: numberValue(value.stepNo) ?? undefined,
    version: numberValue(value.version) ?? 0,
    title: stringValue(value.title) ?? "需要补充信息",
    prompt: stringValue(value.prompt) ?? "请补充必要信息后继续。",
    fields,
    summary,
    submitLabel: stringValue(value.submitLabel) ?? undefined,
    cancelLabel: stringValue(value.cancelLabel) ?? undefined,
    legacy
  };
}

function parseQuestionFields(raw: unknown): QuestionField[] {
  let fields: unknown = raw;
  if (typeof fields === "string") fields = safeJson<unknown>(fields);
  if (fields && typeof fields === "object" && !Array.isArray(fields) && "fields" in fields) {
    fields = (fields as { fields?: unknown }).fields;
  }
  return Array.isArray(fields)
    ? fields.map((field, index) => normalizeQuestionField(field, index))
      .filter((field): field is QuestionField => field !== null)
    : [];
}

function parseWorkflowCheckpoint(payload: AgentItemPayload): WorkflowCheckpointState | null {
  if (payload.kind !== "WORKFLOW_CHECKPOINT") return null;
  const value = recordValue(payload.data);
  if (!value) return null;
  const checkpointId = stringValue(value.checkpointId);
  const runId = stringValue(value.runId);
  const nodeId = stringValue(value.nodeId);
  const actionType = stringValue(value.actionType);
  const orderId = stringValue(value.orderId);
  const factsFingerprint = stringValue(value.factsFingerprint);
  if (!checkpointId || !runId || !nodeId || !actionType || !orderId || !factsFingerprint) return null;
  return {
    kind: "WORKFLOW_CHECKPOINT",
    checkpointId,
    runId,
    turnId: stringValue(value.turnId),
    status: stringValue(value.status) ?? "OPEN",
    version: numberValue(value.version) ?? 0,
    nodeId,
    actionType,
    orderId,
    impactSummary: stringValue(value.impactSummary) ?? "",
    factsFingerprint,
    decision: stringValue(value.decision)
  };
}

function parseQuestionAnswer(payload: AgentItemPayload): QuestionAnswerFact | null {
  if (payload.kind !== "QUESTION_ANSWER") return null;
  const value = recordValue(payload.data);
  const questionId = stringValue(value?.questionId);
  if (!questionId) return null;
  const resumeTarget = stringValue(value?.resumeTarget);
  const action = stringValue(value?.action);
  return { questionId, runId: stringValue(value?.runId), resumeTarget, action };
}

function parseWorkflowDecision(payload: AgentItemPayload): WorkflowDecisionFact | null {
  if (payload.kind !== "WORKFLOW_DECISION") return null;
  const value = recordValue(payload.data);
  const runId = stringValue(value?.runId);
  const checkpointId = stringValue(value?.checkpointId);
  const decision = stringValue(value?.decision);
  if (!runId || !checkpointId || !decision) return null;
  return {
    runId,
    checkpointId,
    expectedVersion: numberValue(value?.expectedVersion) ?? undefined,
    decision,
    factsFingerprint: stringValue(value?.factsFingerprint)
  };
}

function parseInteraction(value: unknown): AgentInteraction | null {
  const data = recordValue(value);
  if (!data) return null;
  const type = stringValue(data?.type);
  if (type === "QUESTION_CARD") {
    if (data?.legacy === true) return null;
    const question = parseQuestion({
      schemaVersion: 1,
      kind: "QUESTION_CARD",
      data: { ...data, questionId: data.questionId ?? data.interactionId } as never
    });
    return question ? { type: "QUESTION_CARD", question } : null;
  }
  if (type === "WORKFLOW_CHECKPOINT") {
    const checkpoint = parseWorkflowCheckpoint({
      schemaVersion: 1,
      kind: "WORKFLOW_CHECKPOINT",
      data: { ...data, checkpointId: data.checkpointId ?? data.interactionId } as never
    });
    return checkpoint ? { type: "WORKFLOW_CHECKPOINT", checkpoint } : null;
  }
  return null;
}

function findOpenInteraction(items: AgentItem[]): AgentInteraction | null {
  let current: AgentInteraction | null = null;
  const pendingAnswers = new Map<string, string>();
  const ordered = items.every((item, index) => index === 0 || item.sequence >= items[index - 1].sequence)
    ? items
    : [...items].sort((left, right) => left.sequence - right.sequence);
  for (const item of ordered) {
    if (item.type === "QUESTION_CARD") {
      const question = parseQuestion(item.payload);
      if (question) current = { type: "QUESTION_CARD", question };
      continue;
    }
    if (item.type === "WORKFLOW_CHECKPOINT") {
      const checkpoint = parseWorkflowCheckpoint(item.payload);
      if (checkpoint) current = { type: "WORKFLOW_CHECKPOINT", checkpoint };
      continue;
    }
    if (item.type === "QUESTION_ANSWER") {
      const answer = parseQuestionAnswer(item.payload);
      if (answer && item.turnId && current?.type === "QUESTION_CARD"
        && current.question.questionId === answer.questionId) {
        // 回答 Item 先于执行结果到达；只有对应 Turn 成功完成才关闭问题，
        // 版本冲突、超时或取消释放回答时仍需让用户看到可重试的 QuestionCard。
        pendingAnswers.set(answer.questionId, item.turnId);
      }
      continue;
    }
    if (item.type === "WORKFLOW_DECISION") {
      const decision = parseWorkflowDecision(item.payload);
      if (decision && current?.type === "WORKFLOW_CHECKPOINT"
        && current.checkpoint.checkpointId === decision.checkpointId) current = null;
      continue;
    }
    if (item.type === "WORKFLOW_ANSWER") {
      const data = recordValue(item.payload.data);
      const questionId = stringValue(data?.questionId);
      if (questionId && current?.type === "QUESTION_CARD" && current.question.questionId === questionId) current = null;
      continue;
    }
    if (item.type === "WORKFLOW_RESULT") {
      const data = recordValue(item.payload.data);
      const resultStatus = stringValue(data?.status);
      if (current?.type === "QUESTION_CARD"
        && current.question.resumeTarget === "WORKFLOW"
        && current.question.runId && resultStatus
        && ["ANSWERED", "CANCELLED"].includes(resultStatus)
        && current.question.runId === stringValue(data?.runId)) {
        current = null;
      }
      continue;
    }
    if (item.type === "TURN_STATE" && item.payload.kind === "TURN_STATE") {
      const data = recordValue(item.payload.data);
      const status = stringValue(data?.status);
      const questionId = [...pendingAnswers.entries()]
        .find(([, answerTurnId]) => answerTurnId === item.turnId)?.[0];
      if (questionId && current?.type === "QUESTION_CARD"
        && current.question.questionId === questionId
        && status === "COMPLETED") {
        current = null;
        pendingAnswers.delete(questionId);
      } else if (questionId && status
        && ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(status)) {
        pendingAnswers.delete(questionId);
      }
    }
  }
  return current;
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
      maxAttempts: numberValue(data?.maxAttempts) ?? undefined,
      nextAttemptAt: stringValue(data?.nextAttemptAt) ?? undefined,
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

function workflowResultRawStatus(value: unknown): string | null {
  const data = recordValue(value);
  return stringValue(data?.status) ?? (typeof value === "string" ? stringValue(value) : null);
}

/** 将 Workflow 的业务回执映射为 Turn 状态，避免子 Turn 的完成状态覆盖真实失败或等待。 */
function workflowResultTurnStatus(value: unknown): AgentTurnStatus {
  const status = workflowResultRawStatus(value);
  switch (status) {
    case "FAILED":
    case "MANUAL_RETRY_REQUIRED":
    case "ORDER_NOT_FOUND":
    case "ORDER_NOT_OWNED":
    case "ORDER_TEMPORARY_FAILURE":
    case "ORDER_FACTS_UNAVAILABLE":
    case "FACTS_CHANGED_ACTION_NOT_ALLOWED":
      return "FAILED";
    case "WAITING_USER_INPUT":
    case "FACTS_CHANGED":
      return "WAITING_USER_INPUT";
    case "WAITING_EXTERNAL_ACTION":
    case "APPROVED":
      return "WAITING_EXTERNAL_ACTION";
    case "CANCELLED":
    case "REJECTED":
    case "ANSWERED":
    case "COMPLETED":
      return "COMPLETED";
    default:
      // 旧版 WORKFLOW_RESULT 可能只是展示文案，沿用历史“已完成”投影。
      return "COMPLETED";
  }
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function numberValue(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string" || value.trim() === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function parseOrderCard(value: unknown): OrderCard | null {
  const data = recordValue(value);
  const orderId = stringValue(data?.orderId);
  // 订单搜索适配器使用 orderStatus，Workflow 回执使用 status；两者都属于同一受控订单事实。
  const status = stringValue(data?.orderStatus) ?? stringValue(data?.status);
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
    || !["QUERY_LOGISTICS", "REFRESH_ORDER", "REFUND", "EXPEDITE", "DELETE_ORDER", "HIDE_ORDER", "RESTORE_ORDER"].includes(actionType)) {
    return null;
  }
  return { sourceTurnId, orderId, actionType: actionType as OrderActionType };
}

function parseContinuation(item: AgentItem): AgentContinuationFact | null {
  if (item.type !== "AGENT_CONTINUATION") return null;
  const data = recordValue(item.payload.data);
  const rootTurnId = stringValue(data?.rootTurnId);
  const parentTurnId = stringValue(data?.parentTurnId);
  const triggerRunId = stringValue(data?.triggerRunId);
  const triggerStatus = stringValue(data?.triggerStatus);
  const triggerSequence = numberValue(data?.triggerSequence);
  const cycleNo = numberValue(data?.cycleNo);
  if (!rootTurnId || !parentTurnId || !triggerRunId || !triggerStatus
    || triggerSequence === null || cycleNo === null) return null;
  return {
    rootTurnId,
    parentTurnId,
    triggerRunId,
    triggerCommandId: stringValue(data?.triggerCommandId),
    triggerStatus,
    triggerSequence,
    cycleNo
  };
}

function parseDecision(item: AgentItem): AgentDecisionFact | null {
  if (item.type !== "AGENT_DECISION") return null;
  const data = recordValue(item.payload.data);
  const decision = stringValue(data?.decision);
  if (!decision) return null;
  return {
    decision,
    cycleNo: numberValue(data?.cycleNo) ?? undefined,
    runId: stringValue(data?.runId),
    code: stringValue(data?.code),
    correctionAttempt: data?.correctionAttempt === true
  };
}

function parseWorkflowStep(item: AgentItem): WorkflowStepFact | null {
  if (item.type !== "WORKFLOW_STEP") return null;
  const data = recordValue(item.payload.data);
  const node = stringValue(data?.node);
  const status = stringValue(data?.status);
  if (!node || !status) return null;
  return {
    runId: stringValue(data?.runId) ?? undefined,
    node,
    status,
    branch: stringValue(data?.branch),
    code: stringValue(data?.code),
    elapsedMillis: numberValue(data?.elapsedMillis) ?? undefined
  };
}

function workflowRunFromItem(item: AgentItem): string | null {
  if (!["QUESTION_CARD", "QUESTION_ANSWER", "WORKFLOW_CHECKPOINT", "WORKFLOW_DECISION", "WORKFLOW_QUESTION", "WORKFLOW_ANSWER", "WORKFLOW_RESULT"].includes(item.type)) return null;
  const data = recordValue(item.payload.data);
  return stringValue(data?.runId);
}

function buildActivities(items: AgentItem[]): BusinessProgress[] {
  const externalSucceededTurns = new Set<string>();
  const externalSucceededRuns = new Set<string>();
  const continuationRuns = new Map<string, string>();
  const workflowResultStatuses = new Map<string, AgentTurnStatus>();
  return items
    .map((item): BusinessProgress | null => {
      const data = recordValue(item.payload.data);
      if (item.type === "AGENT_CONTINUATION" && item.turnId) {
        const continuation = parseContinuation(item);
        if (continuation) continuationRuns.set(item.turnId, continuation.triggerRunId);
      }
      if (item.type === "EXTERNAL_ACTION_STATUS" && data?.status === "SUCCEEDED") {
        if (item.turnId) externalSucceededTurns.add(item.turnId);
        const runId = stringValue(data?.runId);
        if (runId) externalSucceededRuns.add(runId);
        // 外部动作成功是对先前 APPROVED/WAITING 回执的更新，允许最终
        // TURN_STATE=COMPLETED 收敛，不被旧的 Workflow 结果挡住。
        if (item.turnId) workflowResultStatuses.delete(item.turnId);
      }
      const runId = stringValue(data?.runId)
        ?? (item.turnId ? continuationRuns.get(item.turnId) ?? null : null);
      const externalSucceeded = (item.turnId ? externalSucceededTurns.has(item.turnId) : false)
        || (runId !== null && externalSucceededRuns.has(runId));
      if ((item.type === "ERROR"
        || (item.type === "TURN_STATE" && data?.status === "FAILED"))
        && externalSucceeded) {
        return { id: `${item.itemId}-continuation`, label: "业务操作已完成", detail: "后续 Agent 续接未完成", status: "DONE", sequence: item.sequence };
      }
      if (item.type === "TURN_STATE" && typeof data?.status === "string") {
        const status = data.status;
        const workflowResultStatus = item.turnId
          ? workflowResultStatuses.get(item.turnId) : undefined;
        const effectiveStatus = status === "COMPLETED"
          && workflowResultStatus && workflowResultStatus !== "COMPLETED"
          ? workflowResultStatus : status;
        const state = effectiveStatus === "WAITING_USER_INPUT" || effectiveStatus === "WAITING_EXTERNAL_ACTION"
          ? "WAITING"
          : effectiveStatus === "COMPLETED" ? "DONE"
            : ["FAILED", "CANCELLED", "TIMED_OUT"].includes(effectiveStatus) ? "ERROR" : "ACTIVE";
        const label = ({
          QUEUED: "请求已排队",
          ACTIVE: "正在分析请求",
          WAITING_USER_INPUT: "等待你的确认",
          WAITING_EXTERNAL_ACTION: "正在处理业务操作",
          COMPLETED: "请求已完成",
          CANCELLED: "请求已取消",
          TIMED_OUT: "请求处理超时",
          FAILED: "请求未能完成"
        } as Record<string, string>)[effectiveStatus] ?? "正在处理请求";
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
      if (item.type === "QUESTION_CARD") {
        const question = parseQuestion(item.payload);
        return { id: `${item.itemId}-question`, label: "等待补充信息", detail: question?.title ?? null, status: "WAITING", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_CHECKPOINT") {
        const checkpoint = parseWorkflowCheckpoint(item.payload);
        return { id: `${item.itemId}-checkpoint`, label: "等待确认执行", detail: checkpoint?.actionType ?? null, status: "WAITING", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_QUESTION") {
        const question = parseQuestion(item.payload);
        return { id: `${item.itemId}-legacy-question`, label: "历史问题卡片", detail: question?.title ?? null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "QUESTION_ANSWER") {
        const answer = parseQuestionAnswer(item.payload);
        return answer ? { id: `${item.itemId}-question-answer`, label: "已收到问题回答", detail: answer.action ?? null, status: "DONE", sequence: item.sequence } : null;
      }
      if (item.type === "WORKFLOW_ANSWER") {
        return { id: `${item.itemId}-answer`, label: "已收到你的选择", detail: null, status: "DONE", sequence: item.sequence };
      }
      if (item.type === "WORKFLOW_DECISION") {
        const decision = parseWorkflowDecision(item.payload);
        return decision ? { id: `${item.itemId}-workflow-decision`, label: decision.decision === "APPROVE" ? "已确认执行" : "已拒绝执行", detail: decision.decision, status: decision.decision === "APPROVE" ? "DONE" : "ERROR", sequence: item.sequence } : null;
      }
      if (item.type === "WORKFLOW_RESULT") {
        const resultStatus = workflowResultRawStatus(item.payload.data);
        const mapped = workflowResultTurnStatus(item.payload.data);
        if (item.turnId) workflowResultStatuses.set(item.turnId, mapped);
        const rejected = resultStatus === "REJECTED" || resultStatus === "CANCELLED";
        const waiting = mapped === "WAITING_USER_INPUT" || mapped === "WAITING_EXTERNAL_ACTION";
        const failed = mapped === "FAILED";
        const label = failed ? "售后流程未完成"
          : waiting ? (mapped === "WAITING_USER_INPUT" ? "等待补充信息" : "等待外部系统处理")
            : rejected ? "已取消业务操作" : "售后流程已完成";
        return { id: `${item.itemId}-result`, label, detail: null,
          status: failed ? "ERROR" : waiting ? "WAITING" : "DONE", sequence: item.sequence };
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
      if (item.type === "WORKFLOW_STEP") {
        const step = parseWorkflowStep(item);
        if (!step) return null;
        const status = step.status === "COMPLETED" || step.status === "DONE" ? "DONE"
          : step.status === "ERROR" || step.status === "FAILED" ? "ERROR"
            : step.status === "WAITING" ? "WAITING" : "ACTIVE";
        const labels: Record<string, string> = {
          RESOLVE_ORDER: "已解析目标订单",
          VERIFY_FACTS: "已核验订单与物流事实",
          SWITCH_REQUIREMENTS: "已判断业务分支",
          AUTHORIZE: "等待授权",
          EXECUTE_ACTION: "正在执行外部操作",
          VERIFY_OUTCOME: "正在核验操作结果",
          HANDOFF_AGENT: "已交回 Agent 决策"
        };
        return { id: `${item.itemId}-workflow-step`, label: labels[step.node] ?? step.node,
          detail: step.branch ?? step.code ?? null, status, sequence: item.sequence };
      }
      if (item.type === "AGENT_DECISION") {
        const decision = parseDecision(item);
        if (!decision) return null;
        const labels: Record<string, string> = {
          FINISH: "Agent 已完成本轮判断",
          START_WORKFLOW: "Agent 已启动业务流程",
          ASK_USER: "等待用户补充信息",
          WAIT_USER: "等待用户补充信息",
          STOP_LIMIT: "已达到自动决策上限",
          FALLBACK: "已降级为可控结果"
        };
        return { id: `${item.itemId}-agent-decision`, label: labels[decision.decision] ?? "Agent 已作出决策",
          detail: decision.code ?? null, status: decision.decision === "FALLBACK" ? "ERROR"
            : ["ASK_USER", "WAIT_USER"].includes(decision.decision) ? "WAITING" : "DONE", sequence: item.sequence };
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
  const externalSucceededTurns = new Set<string>();
  const externalSucceededRuns = new Set<string>();
  const continuationRuns = new Map<string, string>();
  const workflowResultStatuses = new Map<string, AgentTurnStatus>();
  const current: ThreadViewTurn = {
    turnId,
    userMessage: "",
    content: "",
    status: "ACTIVE",
    error: null,
    errorCode: null,
    continuationWarning: null,
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
    legacyQuestion: null,
    workflowCheckpoint: null,
    sourceTurnId: null,
    inputKind: "MESSAGE",
    workflowSteps: [],
    decisions: [],
    continuation: null
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
    if (item.type === "QUESTION_ANSWER") {
      const answer = parseQuestionAnswer(item.payload);
      if (answer) {
        current.inputKind = "QUESTION_ANSWER";
        if (current.question?.questionId === answer.questionId) current.question = null;
      }
    }
    if (item.type === "WORKFLOW_DECISION") {
      const decision = parseWorkflowDecision(item.payload);
      if (decision) {
        current.inputKind = "WORKFLOW_DECISION";
        if (current.workflowCheckpoint?.checkpointId === decision.checkpointId) current.workflowCheckpoint = null;
      }
    }
    if (item.type === "AGENT_CONTINUATION") {
      const continuation = parseContinuation(item);
      if (continuation) {
        current.continuation = continuation;
        if (item.turnId) continuationRuns.set(item.turnId, continuation.triggerRunId);
        current.sourceTurnId = continuation.rootTurnId;
        current.inputKind = "AGENT_CONTINUATION";
      }
    }
    if (item.type === "WORKFLOW_STEP") {
      const step = parseWorkflowStep(item);
      if (step) current.workflowSteps.push(step);
    }
    if (item.type === "AGENT_DECISION") {
      const decision = parseDecision(item);
      if (decision) current.decisions.push(decision);
    }
    if (item.type === "ERROR") {
      const data = recordValue(item.payload.data);
      const runId = stringValue(data?.runId)
        ?? (item.turnId ? continuationRuns.get(item.turnId) ?? null : null);
      const externalSucceeded = (item.turnId ? externalSucceededTurns.has(item.turnId) : false)
        || (runId !== null && externalSucceededRuns.has(runId));
      if (externalSucceeded) {
        current.continuationWarning = "业务操作已完成，后续 Agent 续接未完成；可以继续提问或稍后查看。";
      } else {
        current.error = payloadText(item.payload);
        const errorData = recordValue(item.payload.data);
        current.errorCode = stringValue(errorData?.code)
          ?? (current.error === "AGENT_DECISION_MISSING" ? current.error : current.errorCode);
        current.status = "FAILED";
        current.finishedAt = item.createdAt;
      }
    }
    if (item.type === "TURN_STATE" && item.payload.kind === "TURN_STATE") {
      const state = item.payload.data;
      if (state && typeof state === "object" && "status" in state && typeof state.status === "string") {
        const status = state.status as AgentTurnStatus;
        const stateData = recordValue(item.payload.data);
        current.errorCode = stringValue(stateData?.errorCode) ?? current.errorCode;
        const runId = stringValue(stateData?.runId)
          ?? (item.turnId ? continuationRuns.get(item.turnId) ?? null : null);
        const externalSucceeded = (item.turnId ? externalSucceededTurns.has(item.turnId) : false)
          || (runId !== null && externalSucceededRuns.has(runId));
        if (status === "FAILED" && externalSucceeded) {
          current.continuationWarning = "业务操作已完成，后续 Agent 续接未完成；可以继续提问或稍后查看。";
        } else {
          const workflowResultStatus = item.turnId
            ? workflowResultStatuses.get(item.turnId) : undefined;
          // Workflow 引擎完成回答子 Turn 后仍会写入 TURN_STATE=COMPLETED；
          // 该技术状态不能覆盖同一 Turn 已记录的 FAILED/WAITING 回执。
          current.status = status === "COMPLETED"
            && workflowResultStatus && workflowResultStatus !== "COMPLETED"
            ? workflowResultStatus : status;
          if (terminal(status)) current.finishedAt = item.createdAt;
        }
      }
    }
    if (item.type === "QUESTION_CARD") {
      const question = parseQuestion(item.payload);
      if (question) {
        current.workflowRunId = question.runId;
        current.question = question;
      }
      // Question Item 是一个新的开放检查点。它可能出现在已完成的回答子 Turn
      // 之后（历史多步 Workflow 会把子 Turn 折回根 Turn），因此不能被前一个
      // COMPLETED 状态挡住；后续回答的 TURN_STATE 会再次把根 Turn 收敛到终态。
      current.status = "WAITING_USER_INPUT";
    }
    if (item.type === "WORKFLOW_QUESTION") {
      const question = parseQuestion(item.payload);
      if (question) {
        current.workflowRunId = question.runId;
        current.legacyQuestion = question;
      }
    }
    if (item.type === "WORKFLOW_CHECKPOINT") {
      const checkpoint = parseWorkflowCheckpoint(item.payload);
      if (checkpoint) {
        current.workflowRunId = checkpoint.runId;
        current.workflowCheckpoint = checkpoint;
      }
      current.status = "WAITING_USER_INPUT";
    }
    const itemRunId = workflowRunFromItem(item);
    if (itemRunId) current.workflowRunId = itemRunId;
    if (item.type === "EXTERNAL_ACTION_STATUS") {
      const action = parseExternalAction(item.payload);
      if (action) {
        current.workflowRunId = action.runId ?? current.workflowRunId;
        current.externalActionStatus = action.status;
        current.externalActionReceipt = { ...(current.externalActionReceipt ?? {}), ...action.receipt };
        if (action.status === "SUCCEEDED") {
          if (item.turnId) externalSucceededTurns.add(item.turnId);
          if (action.runId) externalSucceededRuns.add(action.runId);
          if (item.turnId) workflowResultStatuses.delete(item.turnId);
        }
        const nextStatus = turnStatusForExternalAction(action.status);
        if (nextStatus && !terminal(current.status)) current.status = nextStatus;
      }
    }
    if (item.type === "WORKFLOW_RESULT") {
      const resultStatus = workflowResultTurnStatus(item.payload.data);
      if (item.turnId) workflowResultStatuses.set(item.turnId, resultStatus);
      current.status = resultStatus;
      if (terminal(resultStatus)) current.finishedAt = item.createdAt;
    }
  }
  return {
    ...current,
    activities: buildActivities(orderedItems),
    orderCards: buildOrderCards(orderedItems),
    logisticsTimelines: buildLogisticsTimelines(orderedItems),
    workflowSteps: current.workflowSteps,
    decisions: current.decisions,
    continuation: current.continuation
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
    // 回答/决策子 Turn 也会携带 runId，但它不是 Workflow 的归属 Turn；否则会覆盖来源 Turn。
    if (turn.workflowRunId && !["QUESTION_ANSWER", "WORKFLOW_ANSWER", "WORKFLOW_DECISION"].includes(turn.inputKind)) {
      runOwners.set(turn.workflowRunId, turn.turnId);
    }
  }
  const resolveTarget = (turn: ThreadViewTurn): string | null => {
    let target = turn.sourceTurnId
      ?? (["QUESTION_ANSWER", "WORKFLOW_ANSWER", "WORKFLOW_DECISION"].includes(turn.inputKind) && turn.workflowRunId
        ? runOwners.get(turn.workflowRunId) ?? null : null);
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
  parseContinuation,
  parseDecision,
  parseLogisticsEvent,
  parseOrderAction,
  parseQuestion,
  parseInteraction,
  parseQuestionAnswer,
  parseWorkflowCheckpoint,
  parseWorkflowDecision,
  parseWorkflowStep,
  payloadText,
  rebuildTurns,
  recordValue,
  terminal,
  workflowResultRawStatus,
  workflowResultTurnStatus,
  workflowRunFromItem,
  findOpenInteraction
};
