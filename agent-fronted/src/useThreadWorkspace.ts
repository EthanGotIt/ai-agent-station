import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { HttpRequestError, readJsonResponse } from "./http";
import { appendSseChunk } from "./sse";
import {
  createOpenInteractionIndex,
  createThreadProjectionCache,
  findOpenInteraction,
  normalizeItem,
  parseInteraction,
  parseExternalAction,
  rebuildTurns,
  terminal
} from "./threadProjection";
import { threadWorkspaceApi } from "./threadWorkspaceApi";
import type {
  AgentItem,
  AgentItemPage,
  AgentItemWire,
  AgentInteraction,
  AgentThread,
  AgentThreadEvent,
  AgentTurnStatus,
  OrderActionType,
  QuestionAnswerAction,
  ThreadViewTurn
} from "./threadTypes";
import type { OpenInteractionIndex, ThreadProjectionCache } from "./threadProjection";

const API = "/api/agent";
const SSE_READ_IDLE_TIMEOUT_MS = 45_000;
type ExecutionReplayStatus = "idle" | "loading" | "loaded" | "failed";

function isReplayItem(value: unknown): value is AgentItemWire {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<AgentItemWire>;
  return typeof item.itemId === "string" && item.itemId.trim().length > 0
    && typeof item.type === "string" && item.type.trim().length > 0
    && typeof item.sequence === "number" && Number.isFinite(item.sequence) && item.sequence >= 0
    && typeof item.payload === "string";
}

/** 防止服务端异常游标或重复页让历史恢复循环无法收口。 */
export function replayPageProgress(page: AgentItemPage | null | undefined, afterSequence: number) {
  const safeAfterSequence = Number.isFinite(afterSequence) ? Math.max(0, afterSequence) : 0;
  const rawItems = Array.isArray(page?.items) ? page.items : [];
  const pageItems = rawItems.filter(isReplayItem);
  const invalidPage = pageItems.length !== rawItems.length;
  const pageMaxSequence = pageItems.reduce((max, item) => {
    const sequence = item.sequence;
    return Number.isFinite(sequence) ? Math.max(max, sequence) : max;
  }, safeAfterSequence);
  const responseCursor = Number(page?.nextAfterSequence);
  const nextAfterSequence = Number.isFinite(responseCursor)
    ? Math.max(safeAfterSequence, responseCursor, pageMaxSequence)
    : pageMaxSequence;
  return {
    items: pageItems,
    nextAfterSequence,
    hasMore: !invalidPage && Boolean(page?.hasMore)
      && pageItems.length > 0 && nextAfterSequence > safeAfterSequence
  };
}

function id(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`;
}

/** 从服务端 Item 和 SSE 事实恢复单一 Thread 工作区。 */
export function useThreadWorkspace(userId: string) {
  const [threads, setThreads] = useState<AgentThread[]>([]);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [items, setItems] = useState<AgentItem[]>([]);
  const [interaction, setInteraction] = useState<AgentInteraction | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const cursorRef = useRef(0);
  const itemsRef = useRef<AgentItem[]>([]);
  const itemIndexRef = useRef(new Map<string, AgentItem>());
  const projectionCacheRef = useRef<ThreadProjectionCache>(createThreadProjectionCache());
  const openInteractionIndexRef = useRef<OpenInteractionIndex>(createOpenInteractionIndex());
  const turnsRef = useRef<ThreadViewTurn[]>([]);
  const activeTurnRef = useRef<string | null>(null);
  const eventControllerRef = useRef<AbortController | null>(null);
  const historyControllerRef = useRef<AbortController | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const threadIdRef = useRef<string | null>(null);
  const interactionRef = useRef<AgentInteraction | null>(null);
  const generationRef = useRef(0);
  const retryingRunRef = useRef<string | null>(null);
  const executionCacheRef = useRef(new Map<string, AgentItem[]>());
  const executionLoadingRef = useRef(new Set<string>());
  const [executionReplayStates, setExecutionReplayStates] = useState<Record<string, ExecutionReplayStatus>>({});
  const [retryingRunId, setRetryingRunId] = useState<string | null>(null);
  const turns = useMemo(() => {
    const next = rebuildTurns(items, projectionCacheRef.current);
    turnsRef.current = next;
    return next;
  }, [items]);
  const question = interaction?.type === "QUESTION_CARD" ? interaction.question : null;
  const checkpoint = interaction?.type === "WORKFLOW_CHECKPOINT" ? interaction.checkpoint : null;

  const updateInteraction = useCallback((next: AgentInteraction | null) => {
    interactionRef.current = next;
    setInteraction(next);
  }, []);

  useEffect(() => {
    executionCacheRef.current.clear();
    executionLoadingRef.current.clear();
    itemIndexRef.current.clear();
    projectionCacheRef.current = createThreadProjectionCache();
    openInteractionIndexRef.current = createOpenInteractionIndex();
    setExecutionReplayStates({});
  }, [userId]);

  const applyItems = useCallback((incoming: Array<AgentItem | AgentItemWire>) => {
    const normalizedIncoming = incoming.map(normalizeItem);
    for (const item of normalizedIncoming) cursorRef.current = Math.max(cursorRef.current, item.sequence);
    const current = itemsRef.current;
    const freshById = new Map<string, AgentItem>();
    for (const item of normalizedIncoming) {
      if (!itemIndexRef.current.has(item.itemId)) freshById.set(item.itemId, item);
    }
    const fresh = [...freshById.values()];
    // SSE 重连可能重放已落库 Item；保持引用不变可避免无意义的全量投影和渲染。
    if (fresh.length === 0) return;
    const lastSequence = current.at(-1)?.sequence ?? 0;
    const appendOnly = fresh.length > 0
      && fresh.every((item, index) => item.sequence > lastSequence
        && (index === 0 || item.sequence > fresh[index - 1].sequence));
    const next = appendOnly
      ? [...current, ...fresh]
      : [...new Map([...current, ...fresh].map((item) => [item.itemId, item])).values()]
        .sort((left, right) => left.sequence - right.sequence);
    itemsRef.current = next;
    for (const item of fresh) itemIndexRef.current.set(item.itemId, item);
    setItems(next);
    const derivedInteraction = findOpenInteraction(next, openInteractionIndexRef.current);
    const hasInteractionMutation = fresh.some((value) => {
      const type = typeof value.payload === "string" ? value.type : value.payload.kind;
      // 回答 Item 到达时仍保留卡片，待对应 Turn 的终态/Workflow 回执到达后再收敛。
      return [
        "QUESTION_CARD", "QUESTION_ANSWER", "WORKFLOW_CHECKPOINT", "WORKFLOW_DECISION",
        "WORKFLOW_ANSWER", "WORKFLOW_RESULT", "TURN_STATE"
      ].includes(type);
    });
    if (derivedInteraction || hasInteractionMutation || interactionRef.current === null) {
      updateInteraction(derivedInteraction);
    }
    for (const normalized of fresh) {
      const action = parseExternalAction(normalized.payload);
      if (action && action.runId === retryingRunRef.current && action.status !== "MANUAL_RETRY_REQUIRED") {
        retryingRunRef.current = null;
        setRetryingRunId(null);
        setBusy(false);
      }
      if (normalized.type === "TURN_STATE" && normalized.payload.kind === "TURN_STATE") {
        const state = normalized.payload.data as { status?: unknown };
        const status = state?.status;
        if (status === "WAITING_USER_INPUT") setBusy(false);
        if (typeof status === "string" && terminal(status as AgentTurnStatus)) setBusy(false);
      }
    }
  }, [updateInteraction]);

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
    itemIndexRef.current.clear();
    projectionCacheRef.current = createThreadProjectionCache();
    openInteractionIndexRef.current = createOpenInteractionIndex();
    cursorRef.current = 0;
    setItems([]);
    updateInteraction(null);
    try {
      const recovered: AgentItemWire[] = [];
      let afterSequence = 0;
      let hasMore = true;
      while (hasMore) {
        const page = await threadWorkspaceApi.listItems(userId, nextThreadId, afterSequence, controller.signal);
        if (controller.signal.aborted || generationRef.current !== generation
          || threadIdRef.current !== nextThreadId) return;
        const progress = replayPageProgress(page, afterSequence);
        recovered.push(...progress.items);
        hasMore = progress.hasMore;
        afterSequence = progress.nextAfterSequence;
      }
      if (controller.signal.aborted || generationRef.current !== generation
        || threadIdRef.current !== nextThreadId) return;
      applyItems(recovered);
      const recoveredInteraction = findOpenInteraction(itemsRef.current, openInteractionIndexRef.current);
      try {
        const serverInteraction = await threadWorkspaceApi.getInteraction(userId, nextThreadId, controller.signal);
        if (controller.signal.aborted || generationRef.current !== generation || threadIdRef.current !== nextThreadId) return;
        updateInteraction(serverInteraction ? parseInteraction(serverInteraction) ?? recoveredInteraction : recoveredInteraction);
      } catch {
        if (controller.signal.aborted || generationRef.current !== generation || threadIdRef.current !== nextThreadId) return;
        // Item 历史仍是可恢复事实；交互快照不可用时继续使用本地投影。
        updateInteraction(recoveredInteraction);
      }
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
  }, [applyItems, connect, updateInteraction, userId]);

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
    updateInteraction(null);
    void loadThread(nextThreadId, generation);
  }, [loadThread, updateInteraction]);

  const loadThreads = useCallback(async (generation: number) => {
    setLoading(true);
    setError(null);
    try {
      const page = await threadWorkspaceApi.listThreads(userId);
      if (generationRef.current !== generation) return;
      setThreads(page.items);
      if (page.items[0]) {
        selectThread(page.items[0].threadId);
        return;
      }
      const created = await threadWorkspaceApi.createThread(userId);
      if (generationRef.current !== generation) return;
      setThreads([created]);
      selectThread(created.threadId);
    } catch (failure) {
      if (generationRef.current === generation) {
        setLoading(false);
        setError(failure instanceof Error ? failure.message : "Thread 列表加载失败");
      }
    }
  }, [selectThread, userId]);

  /** 连接失败时重新拉取 Thread，而不是要求用户刷新整页并丢失当前上下文。 */
  const retryConnection = useCallback(() => {
    const currentThreadId = threadIdRef.current;
    if (currentThreadId) {
      if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
      eventControllerRef.current?.abort();
      setError(null);
      const generation = generationRef.current;
      if (threadId === currentThreadId) void connect(currentThreadId, cursorRef.current, generation);
      else void loadThread(currentThreadId, generation);
      return;
    }
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    historyControllerRef.current?.abort();
    eventControllerRef.current?.abort();
    if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
    threadIdRef.current = null;
    setThreadId(null);
    setThreads([]);
    itemsRef.current = [];
    itemIndexRef.current.clear();
    projectionCacheRef.current = createThreadProjectionCache();
    openInteractionIndexRef.current = createOpenInteractionIndex();
    cursorRef.current = 0;
    setItems([]);
    updateInteraction(null);
    setBusy(false);
    void loadThreads(generation);
  }, [connect, loadThread, loadThreads, threadId, updateInteraction]);

  useEffect(() => {
    const generation = generationRef.current;
    void loadThreads(generation);
    return () => {
      generationRef.current += 1;
      historyControllerRef.current?.abort();
      eventControllerRef.current?.abort();
      if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
    };
  }, [loadThreads]);

  const createThread = useCallback(async () => {
    const generation = generationRef.current;
    setError(null);
    try {
      const created = await threadWorkspaceApi.createThread(userId);
      if (generationRef.current !== generation) return;
      setThreads((current) => [created, ...current]);
      selectThread(created.threadId);
    } catch (failure) {
      if (generationRef.current === generation) {
        setError(failure instanceof Error ? failure.message : "Thread 创建失败");
      }
    }
  }, [selectThread, userId]);

  const send = useCallback(async (message: string) => {
    const currentThread = threads.find((thread) => thread.threadId === threadId);
    if (!threadId || !currentThread || currentThread.status !== "ACTIVE" || busy || question) return;
    const requestThreadId = threadId;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await threadWorkspaceApi.submitMessage(userId, requestThreadId, id("turn"), message);
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

  /** 仅重新提交形成 AGENT_DECISION_MISSING 的用户请求；每次都创建新的 Turn/requestId。 */
  const retryTurn = useCallback(async (turnId: string) => {
    const source = turnsRef.current.find((turn) => turn.turnId === turnId);
    const currentThread = threads.find((thread) => thread.threadId === threadId);
    if (!source || source.errorCode !== "AGENT_DECISION_MISSING" || !threadId
      || !currentThread || currentThread.status !== "ACTIVE" || busy || question) return;
    const requestThreadId = threadId;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await threadWorkspaceApi.submitMessage(
        userId, requestThreadId, id("turn-retry"), source.userMessage
      );
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        activeTurnRef.current = accepted.turnId;
      }
    } catch (failure) {
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        setBusy(false);
        setError(failure instanceof Error ? failure.message : "再次尝试提交失败");
      }
    }
  }, [busy, question, threadId, threads, userId]);

  const orderAction = useCallback(async (
    sourceTurnId: string,
    orderId: string,
    actionType: OrderActionType
  ) => {
    const currentThread = threads.find((thread) => thread.threadId === threadId);
    if (!threadId || !currentThread || currentThread.status !== "ACTIVE") {
      setError("当前对话不可执行订单动作。");
      return;
    }
    if (busy) {
      setError("当前操作正在处理中，请稍候。");
      return;
    }
    if (question) {
      setError("请先结束当前确认操作，再查询或处理其他订单。");
      return;
    }
    const requestThreadId = threadId;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await threadWorkspaceApi.submitOrderAction(
        userId, requestThreadId, id("order-action"), sourceTurnId, orderId, actionType
      );
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        activeTurnRef.current = accepted.turnId;
      }
    } catch (failure) {
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        setBusy(false);
        setError(failure instanceof Error ? failure.message : "订单动作提交失败");
      }
    }
  }, [busy, question, threadId, threads, userId]);

  const answer = useCallback(async (
    answers: Record<string, string>,
    action: QuestionAnswerAction = "SUBMIT"
  ) => {
    if (!question || busy) return;
    const requestQuestion = question;
    const requestThreadId = threadIdRef.current;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await threadWorkspaceApi.submitQuestionAnswer(
        userId, requestQuestion.questionId, id("question-answer"), requestQuestion.version, action, answers
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

  const decideCheckpoint = useCallback(async (decision: "APPROVE" | "REJECT") => {
    if (!checkpoint || busy) return;
    const requestCheckpoint = checkpoint;
    const requestThreadId = threadIdRef.current;
    const generation = generationRef.current;
    setBusy(true);
    setError(null);
    try {
      const accepted = await threadWorkspaceApi.decideWorkflowCheckpoint(
        userId, requestCheckpoint.runId, requestCheckpoint.checkpointId, id("workflow-decision"),
        requestCheckpoint.version, decision, requestCheckpoint.factsFingerprint
      );
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        activeTurnRef.current = accepted.turnId;
      }
    } catch (failure) {
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        setBusy(false);
        setError(failure instanceof Error ? failure.message : "执行确认提交失败");
      }
    }
  }, [busy, checkpoint, userId]);

  const cancel = useCallback(async () => {
    const turnId = activeTurnRef.current;
    if (!turnId) return;
    const generation = generationRef.current;
    try {
      await threadWorkspaceApi.cancelTurn(userId, turnId);
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
      await threadWorkspaceApi.retryWorkflow(userId, runId);
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
    const turn = turnsRef.current.find((candidate) => candidate.turnId === turnId);
    const requestThreadId = threadIdRef.current;
    const generation = generationRef.current;
    if (!turn || !terminal(turn.status) || executionCacheRef.current.has(turnId)
      || executionLoadingRef.current.has(turnId) || !requestThreadId) {
      return;
    }
    executionLoadingRef.current.add(turnId);
    setExecutionReplayStates((current) => ({ ...current, [turnId]: "loading" }));
    try {
      const replay = await threadWorkspaceApi.loadExecution(userId, turnId);
      if (generationRef.current !== generation || threadIdRef.current !== requestThreadId) return;
      const timeline = Array.isArray(replay.timeline)
        ? replay.timeline.filter((item) => item && item.turnId === turnId).map(normalizeItem)
        : [];
      executionCacheRef.current.set(turnId, timeline);
      if (timeline.length > 0) applyItems(timeline);
      setExecutionReplayStates((current) => ({ ...current, [turnId]: "loaded" }));
    } catch {
      // 回放接口失败时保留已经从 Items/SSE 恢复的事实，检查器仍可打开。
      if (generationRef.current === generation && threadIdRef.current === requestThreadId) {
        setExecutionReplayStates((current) => ({ ...current, [turnId]: "failed" }));
      }
    } finally {
      executionLoadingRef.current.delete(turnId);
    }
  }, [applyItems, userId]);

  const rename = useCallback(async (nextThreadId: string, title: string) => {
    const target = threads.find((thread) => thread.threadId === nextThreadId);
    if (!target || !title.trim()) return false;
    const generation = generationRef.current;
    try {
      const updated = await threadWorkspaceApi.updateThread(
        userId, nextThreadId, title.trim()
      );
      if (generationRef.current !== generation) return false;
      setThreads((current) => current.map((thread) => thread.threadId === updated.threadId ? updated : thread));
      return true;
    } catch (failure) {
      if (generationRef.current === generation) {
        setError(failure instanceof Error ? failure.message : "Thread 重命名失败");
      }
      return false;
    }
  }, [threads, userId]);

  return {
    answer,
    checkpoint,
    decideCheckpoint,
    busy,
    cancel,
    createThread,
    error,
    executionReplayStates,
    loadExecution,
    items,
    loading,
    orderAction,
    interaction,
    question,
    rename,
    retry,
    retryConnection,
    retryTurn,
    retryingRunId,
    selectThread,
    send,
    threadId,
    threads,
    turns
  };
}
