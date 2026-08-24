import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { HttpRequestError, readJsonResponse, requestJson } from "./http";
import { appendSseChunk } from "./sse";
import type {
  AgentItem,
  AgentItemPayload,
  AgentItemPage,
  AgentItemWire,
  AgentThread,
  AgentThreadEvent,
  AgentThreadPage,
  AgentTurnStatus,
  BusinessProgress,
  ExternalActionReceipt,
  ExternalActionStatus,
  LogisticsEvent,
  LogisticsTimeline,
  OrderCard,
  QuestionField,
  QuestionCardState,
  WorkflowAnswerAction,
  QuestionSummaryLine,
  ThreadViewTurn
} from "./threadTypes";

const API = "/api/agent";
const SSE_READ_IDLE_TIMEOUT_MS = 45_000;
type ExecutionReplayStatus = "idle" | "loading" | "loaded" | "failed";

function id(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`;
}

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

function rebuildTurns(items: AgentItem[]): ThreadViewTurn[] {
  const grouped = new Map<string, ThreadViewTurn>();
  for (const item of items) {
    if (!item.turnId) continue;
    const current = grouped.get(item.turnId) ?? {
      turnId: item.turnId,
      userMessage: "",
      content: "",
      status: "ACTIVE" as AgentTurnStatus,
      error: null,
      startedAt: item.createdAt,
      finishedAt: null,
      workflowRunId: null,
      externalActionStatus: null,
      externalActionReceipt: null,
      items: [],
      activities: [],
      orderCards: [],
      logisticsTimelines: []
    };
    current.items.push(item);
    if (item.type === "USER_MESSAGE") current.userMessage = payloadText(item.payload);
    if (item.type === "ASSISTANT_MESSAGE") current.content = `${current.content}${current.content ? "\n" : ""}${payloadText(item.payload)}`;
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
      if (question) current.workflowRunId = question.runId;
      if (!terminal(current.status)) current.status = "WAITING_USER_INPUT";
    }
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
    grouped.set(item.turnId, current);
  }
  return [...grouped.values()].map((turn) => {
    const orderedItems = [...turn.items].sort((left, right) => left.sequence - right.sequence);
    return {
      ...turn,
      items: orderedItems,
      activities: buildActivities(orderedItems),
      orderCards: buildOrderCards(orderedItems),
      logisticsTimelines: buildLogisticsTimelines(orderedItems)
    };
  });
}

function terminal(status: AgentTurnStatus) {
  return ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(status);
}

/** 从服务端 Item 和 SSE 事实恢复单一 Thread 工作区。 */
export function useThreadWorkspace(userId: string) {
  const [threads, setThreads] = useState<AgentThread[]>([]);
  const [archivedThreads, setArchivedThreads] = useState<AgentThread[]>([]);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [items, setItems] = useState<AgentItem[]>([]);
  const [question, setQuestion] = useState<QuestionCardState | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [archiveLoading, setArchiveLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cursorRef = useRef(0);
  const itemsRef = useRef<AgentItem[]>([]);
  const activeTurnRef = useRef<string | null>(null);
  const eventControllerRef = useRef<AbortController | null>(null);
  const historyControllerRef = useRef<AbortController | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const threadIdRef = useRef<string | null>(null);
  const generationRef = useRef(0);
  const retryingRunRef = useRef<string | null>(null);
  const executionCacheRef = useRef(new Map<string, AgentItem[]>());
  const executionLoadingRef = useRef(new Set<string>());
  const [executionReplayStates, setExecutionReplayStates] = useState<Record<string, ExecutionReplayStatus>>({});
  const [retryingRunId, setRetryingRunId] = useState<string | null>(null);
  const turns = useMemo(() => rebuildTurns(items), [items]);

  useEffect(() => {
    executionCacheRef.current.clear();
    executionLoadingRef.current.clear();
    setExecutionReplayStates({});
  }, [userId]);

  const applyItems = useCallback((incoming: Array<AgentItem | AgentItemWire>) => {
    const byId = new Map(itemsRef.current.map((item) => [item.itemId, item]));
    for (const wire of incoming) {
      const item = normalizeItem(wire);
      byId.set(item.itemId, item);
      cursorRef.current = Math.max(cursorRef.current, item.sequence);
    }
    const next = [...byId.values()].sort((left, right) => left.sequence - right.sequence);
    itemsRef.current = next;
    setItems(next);
    for (const item of incoming) {
      const normalized = normalizeItem(item);
      const action = parseExternalAction(normalized.payload);
      if (action && action.runId === retryingRunRef.current && action.status !== "MANUAL_RETRY_REQUIRED") {
        retryingRunRef.current = null;
        setRetryingRunId(null);
        setBusy(false);
      }
      if (normalized.type === "WORKFLOW_QUESTION") setQuestion(parseQuestion(normalized.payload));
      if (normalized.type === "WORKFLOW_ANSWER") setQuestion(null);
      if (normalized.type === "TURN_STATE" && normalized.payload.kind === "TURN_STATE") {
        const state = normalized.payload.data as { status?: unknown };
        const status = state?.status;
        if (status === "WAITING_USER_INPUT") setBusy(false);
        if (typeof status === "string" && terminal(status as AgentTurnStatus)) setBusy(false);
      }
    }
  }, []);

  const applyEvent = useCallback((event: AgentThreadEvent) => {
    if (!threadIdRef.current || event.threadId !== threadIdRef.current) return;
    if (!event.type.startsWith("item.")) return;
    if (event.sequence >= 0) cursorRef.current = Math.max(cursorRef.current, event.sequence);
    const item: AgentItemWire = {
      itemId: event.itemId ?? event.eventId,
      turnId: event.turnId,
      sequence: event.sequence,
      type: event.type.slice("item.".length).toUpperCase(),
      schemaVersion: 1,
      payload: event.payload,
      createdAt: event.timestamp
    };
    applyItems([item]);
  }, [applyItems]);

  const consumeSse = useCallback(async (
    response: Response,
    onEvent: (event: AgentThreadEvent) => void,
    signal: AbortSignal
  ) => {
    if (!response.body) throw new HttpRequestError("SSE 响应未建立。", "network");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const cancelReader = () => {
      void reader.cancel().catch(() => undefined);
    };
    const readChunk = () => new Promise<ReadableStreamReadResult<Uint8Array>>((resolve, reject) => {
      let settled = false;
      let timeoutId: number | null = null;
      const finish = (complete: () => void) => {
        if (settled) return;
        settled = true;
        if (timeoutId !== null) window.clearTimeout(timeoutId);
        signal.removeEventListener("abort", abort);
        complete();
      };
      const abort = () => {
        cancelReader();
        finish(() => reject(new HttpRequestError("实时事件连接已取消。", "aborted")));
      };
      timeoutId = window.setTimeout(() => {
        cancelReader();
        finish(() => reject(new HttpRequestError("实时事件连接无响应。", "network")));
      }, SSE_READ_IDLE_TIMEOUT_MS);
      signal.addEventListener("abort", abort, { once: true });
      reader.read().then(
        chunk => finish(() => resolve(chunk)),
        failure => finish(() => reject(failure))
      );
    });
    const append = (type: string, data: unknown) => {
      if (type === "ready") return;
      if (!data || typeof data !== "object") return;
      onEvent(data as AgentThreadEvent);
    };
    while (!signal.aborted) {
      const chunk = await readChunk();
      if (chunk.done) break;
      buffer = appendSseChunk(buffer + decoder.decode(chunk.value, { stream: true }), append);
    }
    if (buffer.trim() && !signal.aborted) appendSseChunk(`${buffer}\n\n`, append);
  }, []);

  const connect = useCallback(async (nextThreadId: string, afterSequence: number, generation: number) => {
    const isCurrent = () => generationRef.current === generation && threadIdRef.current === nextThreadId;
    if (!isCurrent()) return;
    eventControllerRef.current?.abort();
    const controller = new AbortController();
    eventControllerRef.current = controller;
    try {
      const response = await fetch(
        `${API}/threads/${encodeURIComponent(nextThreadId)}/events?afterSequence=${afterSequence}`,
        { headers: { "X-User-Id": userId }, signal: controller.signal }
      );
      if (!response.ok) {
        await readJsonResponse(response);
        return;
      }
      if (isCurrent()) setError(null);
      await consumeSse(response, applyEvent, controller.signal);
      if (!controller.signal.aborted && isCurrent()) {
        reconnectTimerRef.current = window.setTimeout(
          () => void connect(nextThreadId, cursorRef.current, generation), 800);
      }
    } catch (failure) {
      if (!controller.signal.aborted && isCurrent()) {
        setError(failure instanceof Error ? failure.message : "实时事件连接失败");
        reconnectTimerRef.current = window.setTimeout(
          () => void connect(nextThreadId, cursorRef.current, generation), 1200);
      }
    }
  }, [applyEvent, consumeSse, userId]);

  useEffect(() => {
    const clearReconnectTimer = () => {
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
    };
    const handleOffline = () => {
      clearReconnectTimer();
      eventControllerRef.current?.abort();
    };
    const handleOnline = () => {
      const nextThreadId = threadIdRef.current;
      if (!nextThreadId) return;
      clearReconnectTimer();
      const generation = generationRef.current;
      reconnectTimerRef.current = window.setTimeout(() => {
        reconnectTimerRef.current = null;
        void connect(nextThreadId, cursorRef.current, generation);
      }, 0);
    };
    window.addEventListener("offline", handleOffline);
    window.addEventListener("online", handleOnline);
    return () => {
      window.removeEventListener("offline", handleOffline);
      window.removeEventListener("online", handleOnline);
    };
  }, [connect]);

  const loadThread = useCallback(async (nextThreadId: string, generation: number) => {
    if (generationRef.current !== generation || threadIdRef.current !== nextThreadId) return;
    historyControllerRef.current?.abort();
    const controller = new AbortController();
    historyControllerRef.current = controller;
    setLoading(true);
    setError(null);
    itemsRef.current = [];
    cursorRef.current = 0;
    setItems([]);
    setQuestion(null);
    try {
      const recovered: AgentItemWire[] = [];
      let afterSequence = 0;
      let hasMore = true;
      while (hasMore) {
        const page = await requestJson<AgentItemPage>(
          `${API}/threads/${encodeURIComponent(nextThreadId)}/items?afterSequence=${afterSequence}&limit=500`,
          { headers: { "X-User-Id": userId }, signal: controller.signal }
        );
        if (controller.signal.aborted || generationRef.current !== generation
          || threadIdRef.current !== nextThreadId) return;
        recovered.push(...page.items);
        hasMore = page.hasMore && page.items.length > 0;
        afterSequence = page.nextAfterSequence;
      }
      if (controller.signal.aborted || generationRef.current !== generation
        || threadIdRef.current !== nextThreadId) return;
      applyItems(recovered);
      setThreadId(nextThreadId);
      setLoading(false);
      void connect(nextThreadId, afterSequence, generation);
    } catch (failure) {
      if (!controller.signal.aborted && generationRef.current === generation
        && threadIdRef.current === nextThreadId) {
        setLoading(false);
        setError(failure instanceof Error ? failure.message : "Thread 历史加载失败");
      }
    } finally {
      if (historyControllerRef.current === controller) historyControllerRef.current = null;
    }
  }, [applyItems, connect, userId]);

  const selectThread = useCallback((nextThreadId: string) => {
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    historyControllerRef.current?.abort();
    eventControllerRef.current?.abort();
    if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
    threadIdRef.current = nextThreadId;
    activeTurnRef.current = null;
    retryingRunRef.current = null;
    setRetryingRunId(null);
    setThreadId(null);
    setBusy(false);
    void loadThread(nextThreadId, generation);
  }, [loadThread]);

  const loadArchivedThreads = useCallback(async () => {
    setArchiveLoading(true);
    try {
      const page = await requestJson<AgentThreadPage>(`${API}/threads?status=ARCHIVED&page=0&size=100`, {
        headers: { "X-User-Id": userId }
      });
      setArchivedThreads(page.items);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "回收站加载失败");
    } finally {
      setArchiveLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    let cancelled = false;
    async function loadThreads() {
      setLoading(true);
      setError(null);
      setArchivedThreads([]);
      try {
        const page = await requestJson<AgentThreadPage>(`${API}/threads?page=0&size=100`, {
          headers: { "X-User-Id": userId }
        });
        if (cancelled) return;
        setThreads(page.items);
        if (page.items[0]) selectThread(page.items[0].threadId);
        else {
          const created = await requestJson<AgentThread>(`${API}/threads`, {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-User-Id": userId },
            body: JSON.stringify({ title: "新的 Agent Thread" })
          });
          if (cancelled) return;
          setThreads([created]);
          selectThread(created.threadId);
        }
      } catch (failure) {
        if (!cancelled) {
          setLoading(false);
          setError(failure instanceof Error ? failure.message : "Thread 列表加载失败");
        }
      }
    }
    void loadThreads();
    return () => {
      cancelled = true;
      generationRef.current += 1;
      historyControllerRef.current?.abort();
      eventControllerRef.current?.abort();
      if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
    };
  }, [selectThread, userId]);

  const createThread = useCallback(async () => {
    setError(null);
    try {
      const created = await requestJson<AgentThread>(`${API}/threads`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ title: "新的 Agent Thread" })
      });
      setThreads((current) => [created, ...current]);
      selectThread(created.threadId);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Thread 创建失败");
    }
  }, [selectThread, userId]);

  const archiveThread = useCallback(async (nextThreadId: string) => {
    const target = threads.find((thread) => thread.threadId === nextThreadId);
    if (!target || target.status !== "ACTIVE") return false;
    try {
      const updated = await requestJson<AgentThread>(`${API}/threads/${encodeURIComponent(nextThreadId)}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ title: target.title, archive: true })
      });
      setThreads((current) => current.filter((thread) => thread.threadId !== updated.threadId));
      setArchivedThreads((current) => [updated, ...current.filter((thread) => thread.threadId !== updated.threadId)]);
      if (threadIdRef.current === updated.threadId) {
        const fallback = threads.find((thread) => thread.threadId !== updated.threadId);
        if (fallback) selectThread(fallback.threadId);
        else await createThread();
      }
      return true;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "对话暂时不能归档");
      return false;
    }
  }, [createThread, selectThread, threads, userId]);

  const restoreThread = useCallback(async (nextThreadId: string) => {
    const target = archivedThreads.find((thread) => thread.threadId === nextThreadId);
    if (!target || target.status !== "ARCHIVED") return false;
    try {
      const updated = await requestJson<AgentThread>(`${API}/threads/${encodeURIComponent(nextThreadId)}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ title: target.title, archive: false })
      });
      setArchivedThreads((current) => current.filter((thread) => thread.threadId !== updated.threadId));
      setThreads((current) => [updated, ...current.filter((thread) => thread.threadId !== updated.threadId)]);
      selectThread(updated.threadId);
      return true;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "对话恢复失败");
      return false;
    }
  }, [archivedThreads, selectThread, userId]);

  const send = useCallback(async (message: string) => {
    const currentThread = threads.find((thread) => thread.threadId === threadId);
    if (!threadId || !currentThread || currentThread.status !== "ACTIVE" || busy || question) return;
    const requestThreadId = threadId;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await requestJson<{ turnId: string }>(`${API}/threads/${encodeURIComponent(requestThreadId)}/turns`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ clientRequestId: id("turn"), message })
      });
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        activeTurnRef.current = accepted.turnId;
      }
    } catch (failure) {
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        setBusy(false);
        setError(failure instanceof Error ? failure.message : "Turn 提交失败");
      }
    }
  }, [busy, question, threadId, threads, userId]);

  const answer = useCallback(async (
    answers: Record<string, string>,
    action: WorkflowAnswerAction = "SUBMIT"
  ) => {
    if (!question || busy) return;
    const requestQuestion = question;
    const requestThreadId = threadIdRef.current;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await requestJson<{ turnId: string }>(
        `${API}/workflow-runs/${encodeURIComponent(requestQuestion.runId)}/questions/${encodeURIComponent(requestQuestion.questionId)}/answers`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-User-Id": userId },
          body: JSON.stringify({ clientRequestId: id("answer"), checkpointId: requestQuestion.checkpointId,
            expectedVersion: requestQuestion.version, action, answers })
        }
      );
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        activeTurnRef.current = accepted.turnId;
      }
    } catch (failure) {
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        setBusy(false);
        setError(failure instanceof Error ? failure.message : "QuestionCard 提交失败");
      }
    }
  }, [busy, question, userId]);

  const cancel = useCallback(async () => {
    const turnId = activeTurnRef.current;
    if (!turnId) return;
    const generation = generationRef.current;
    try {
      await requestJson(`${API}/turns/${encodeURIComponent(turnId)}/cancel`, {
        method: "POST", headers: { "X-User-Id": userId }
      });
    } catch (failure) {
      if (generationRef.current === generation) {
        setError(failure instanceof Error ? failure.message : "取消失败");
      }
    } finally {
      if (generationRef.current === generation) setBusy(false);
    }
  }, [userId]);

  const retry = useCallback(async (runId: string) => {
    if (!runId || busy || retryingRunRef.current === runId) return;
    const generation = generationRef.current;
    retryingRunRef.current = runId;
    setRetryingRunId(runId);
    setBusy(true);
    setError(null);
    try {
      await requestJson(`${API}/workflow-runs/${encodeURIComponent(runId)}/retry`, {
        method: "POST",
        headers: { "X-User-Id": userId }
      });
      if (generationRef.current === generation) setBusy(false);
    } catch (failure) {
      if (generationRef.current === generation) {
        retryingRunRef.current = null;
        setRetryingRunId(null);
        setBusy(false);
        setError(failure instanceof Error ? failure.message : "人工重试提交失败");
      }
    }
  }, [busy, userId]);

  const loadExecution = useCallback(async (turnId: string) => {
    const turn = turns.find((candidate) => candidate.turnId === turnId);
    if (!turn || !terminal(turn.status) || executionCacheRef.current.has(turnId)
      || executionLoadingRef.current.has(turnId)) {
      return;
    }
    executionLoadingRef.current.add(turnId);
    setExecutionReplayStates((current) => ({ ...current, [turnId]: "loading" }));
    try {
      const replay = await requestJson<{ timeline?: AgentItemWire[] }>(
        `${API}/turns/${encodeURIComponent(turnId)}/execution`,
        { headers: { "X-User-Id": userId } }
      );
      const timeline = Array.isArray(replay.timeline)
        ? replay.timeline.filter((item) => item && item.turnId === turnId).map(normalizeItem)
        : [];
      executionCacheRef.current.set(turnId, timeline);
      if (timeline.length > 0) applyItems(timeline);
      setExecutionReplayStates((current) => ({ ...current, [turnId]: "loaded" }));
    } catch {
      // 回放接口失败时保留已经从 Items/SSE 恢复的事实，检查器仍可打开。
      setExecutionReplayStates((current) => ({ ...current, [turnId]: "failed" }));
    } finally {
      executionLoadingRef.current.delete(turnId);
    }
  }, [applyItems, turns, userId]);

  const rename = useCallback(async (nextThreadId: string, title: string) => {
    const target = threads.find((thread) => thread.threadId === nextThreadId)
      ?? archivedThreads.find((thread) => thread.threadId === nextThreadId);
    if (!target || !title.trim()) return false;
    try {
      const updated = await requestJson<AgentThread>(`${API}/threads/${encodeURIComponent(nextThreadId)}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ title: title.trim(), archive: target.status === "ARCHIVED" })
      });
      setThreads((current) => current.map((thread) => thread.threadId === updated.threadId ? updated : thread));
      setArchivedThreads((current) => current.map((thread) => thread.threadId === updated.threadId ? updated : thread));
      return true;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Thread 重命名失败");
      return false;
    }
  }, [archivedThreads, threads, userId]);

  return {
    answer,
    archiveLoading,
    archiveThread,
    archivedThreads,
    busy,
    cancel,
    createThread,
    error,
    executionReplayStates,
    loadExecution,
    items,
    loadArchivedThreads,
    loading,
    question,
    rename,
    restoreThread,
    retry,
    retryingRunId,
    selectThread,
    send,
    threadId,
    threads,
    turns
  };
}
