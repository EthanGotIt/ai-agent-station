import { useCallback, useEffect, useRef, useState } from "react";
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
  QuestionCardState,
  ThreadViewTurn
} from "./threadTypes";

const API = "/api/agent";

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
  return {
    runId: value.runId,
    questionId: value.questionId,
    checkpointId: value.checkpointId,
    version: Number(value.version ?? 0),
    title: value.title ?? "需要确认",
    prompt: value.prompt ?? "请确认是否继续。",
    fields: Array.isArray(fields) ? fields as QuestionCardState["fields"] : []
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
      finishedAt: null
    };
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
    if (item.type === "WORKFLOW_QUESTION") current.status = "WAITING_USER_INPUT";
    if (item.type === "EXTERNAL_ACTION_STATUS") current.status = "WAITING_EXTERNAL_ACTION";
    if (item.type === "WORKFLOW_RESULT") current.status = "COMPLETED";
    grouped.set(item.turnId, current);
  }
  return [...grouped.values()];
}

function terminal(status: AgentTurnStatus) {
  return ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"].includes(status);
}

/** 从服务端 Item 和 SSE 事实恢复单一 Thread 工作区。 */
export function useThreadWorkspace(userId: string) {
  const [threads, setThreads] = useState<AgentThread[]>([]);
  const [threadId, setThreadId] = useState<string | null>(null);
  const [items, setItems] = useState<AgentItem[]>([]);
  const [turns, setTurns] = useState<ThreadViewTurn[]>([]);
  const [trace, setTrace] = useState<AgentThreadEvent[]>([]);
  const [question, setQuestion] = useState<QuestionCardState | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const cursorRef = useRef(0);
  const itemsRef = useRef<AgentItem[]>([]);
  const activeTurnRef = useRef<string | null>(null);
  const eventControllerRef = useRef<AbortController | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const threadIdRef = useRef<string | null>(null);

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
    setTurns(rebuildTurns(next));
    for (const item of incoming) {
      const normalized = normalizeItem(item);
      if (normalized.type === "WORKFLOW_QUESTION") setQuestion(parseQuestion(normalized.payload));
      if (normalized.type === "WORKFLOW_ANSWER") setQuestion(null);
    }
  }, []);

  const applyEvent = useCallback((event: AgentThreadEvent) => {
    if (event.sequence >= 0) cursorRef.current = Math.max(cursorRef.current, event.sequence);
    if (event.type.startsWith("item.")) {
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
      setTrace((current) => current.some((value) => value.eventId === event.eventId)
        ? current
        : [...current.slice(-99), event]);
      return;
    }
    if (event.type.startsWith("turn.")) {
      const status = event.type.slice("turn.".length).toUpperCase() as AgentTurnStatus;
      setTurns((current) => current.map((turn) => turn.turnId === event.turnId
        ? { ...turn, status, finishedAt: terminal(status) ? event.timestamp : turn.finishedAt }
        : turn));
      if (status === "WAITING_USER_INPUT") setBusy(false);
      if (terminal(status)) setBusy(false);
      setTrace((current) => current.some((value) => value.eventId === event.eventId)
        ? current
        : [...current.slice(-99), event]);
    }
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
    const append = (type: string, data: unknown) => {
      if (type === "ready") return;
      if (!data || typeof data !== "object") return;
      onEvent(data as AgentThreadEvent);
    };
    while (!signal.aborted) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer = appendSseChunk(buffer + decoder.decode(chunk.value, { stream: true }), append);
    }
    if (buffer.trim() && !signal.aborted) appendSseChunk(`${buffer}\n\n`, append);
  }, []);

  const connect = useCallback(async (nextThreadId: string, afterSequence: number) => {
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
      await consumeSse(response, applyEvent, controller.signal);
      if (!controller.signal.aborted && threadIdRef.current === nextThreadId) {
        reconnectTimerRef.current = window.setTimeout(() => void connect(nextThreadId, cursorRef.current), 800);
      }
    } catch (failure) {
      if (!controller.signal.aborted && threadIdRef.current === nextThreadId) {
        setError(failure instanceof Error ? failure.message : "实时事件连接失败");
        reconnectTimerRef.current = window.setTimeout(() => void connect(nextThreadId, cursorRef.current), 1200);
      }
    }
  }, [applyEvent, consumeSse, userId]);

  const loadThread = useCallback(async (nextThreadId: string) => {
    setLoading(true);
    setError(null);
    itemsRef.current = [];
    cursorRef.current = 0;
    setItems([]);
    setTurns([]);
    setTrace([]);
    setQuestion(null);
    try {
      const page = await requestJson<AgentItemPage>(
        `${API}/threads/${encodeURIComponent(nextThreadId)}/items?afterSequence=0&limit=500`,
        { headers: { "X-User-Id": userId } }
      );
      applyItems(page.items);
      threadIdRef.current = nextThreadId;
      setThreadId(nextThreadId);
      setLoading(false);
      void connect(nextThreadId, page.nextAfterSequence);
    } catch (failure) {
      setLoading(false);
      setError(failure instanceof Error ? failure.message : "Thread 历史加载失败");
    }
  }, [applyItems, connect, userId]);

  const selectThread = useCallback((nextThreadId: string) => {
    eventControllerRef.current?.abort();
    if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
    threadIdRef.current = nextThreadId;
    activeTurnRef.current = null;
    setBusy(false);
    void loadThread(nextThreadId);
  }, [loadThread]);

  useEffect(() => {
    let cancelled = false;
    async function loadThreads() {
      setLoading(true);
      setError(null);
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

  const send = useCallback(async (message: string) => {
    if (!threadId || busy || question) return;
    setBusy(true);
    setError(null);
    try {
      const accepted = await requestJson<{ turnId: string }>(`${API}/threads/${encodeURIComponent(threadId)}/turns`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ clientRequestId: id("turn"), message })
      });
      activeTurnRef.current = accepted.turnId;
    } catch (failure) {
      setBusy(false);
      setError(failure instanceof Error ? failure.message : "Turn 提交失败");
    }
  }, [busy, question, threadId, userId]);

  const answer = useCallback(async (answers: Record<string, string>) => {
    if (!question || busy) return;
    setBusy(true);
    setError(null);
    try {
      const accepted = await requestJson<{ turnId: string }>(
        `${API}/workflow-runs/${encodeURIComponent(question.runId)}/questions/${encodeURIComponent(question.questionId)}/answers`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-User-Id": userId },
          body: JSON.stringify({ clientRequestId: id("answer"), ...question, answers })
        }
      );
      activeTurnRef.current = accepted.turnId;
    } catch (failure) {
      setBusy(false);
      setError(failure instanceof Error ? failure.message : "QuestionCard 提交失败");
    }
  }, [busy, question, userId]);

  const cancel = useCallback(async () => {
    const turnId = activeTurnRef.current;
    if (!turnId) return;
    try {
      await requestJson(`${API}/turns/${encodeURIComponent(turnId)}/cancel`, {
        method: "POST", headers: { "X-User-Id": userId }
      });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "取消失败");
    } finally {
      setBusy(false);
    }
  }, [userId]);

  const rename = useCallback(async (title: string) => {
    if (!threadId || !title.trim()) return;
    try {
      const updated = await requestJson<AgentThread>(`${API}/threads/${encodeURIComponent(threadId)}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ title: title.trim(), archive: false })
      });
      setThreads((current) => current.map((thread) => thread.threadId === updated.threadId ? updated : thread));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Thread 重命名失败");
    }
  }, [threadId, userId]);

  useEffect(() => () => {
    eventControllerRef.current?.abort();
    if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current);
  }, []);

  return {
    answer,
    busy,
    cancel,
    createThread,
    error,
    items,
    loading,
    question,
    rename,
    selectThread,
    send,
    threadId,
    threads,
    trace,
    turns
  };
}
