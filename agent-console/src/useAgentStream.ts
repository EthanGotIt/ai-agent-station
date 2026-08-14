import { useCallback, useEffect, useRef, useState } from "react";
import { HttpRequestError, readJsonResponse, requestJson } from "./http";
import { appendSseChunk } from "./sse";
import type {
  ConversationTurn,
  ConversationTurnStatus,
  Intervention,
  MemoryOptions,
  RunTraceEvent,
  TimelineEvent,
  WorkflowQuestionEvent
} from "./types";

const API = "/api/v1/agent";
const TRACE_EVENT_TYPES = new Set<RunTraceEvent["type"]>(["route", "node", "progress", "tool", "done", "error"]);

type StreamRequest = {
  requestId: string;
  sessionId: string;
  message: string;
  memory: MemoryOptions;
};

type AnswerRequest = {
  requestId: string;
  sessionId: string;
  runId: string;
  questionId: string;
  checkpointId: string;
  expectedVersion: number;
  answers: Record<string, string>;
  memory: MemoryOptions;
};

function requestId(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`;
}

function traceValue(data: unknown) {
  return typeof data === "string" ? data : JSON.stringify(data);
}

function completionStatus(value: unknown): ConversationTurnStatus {
  if (value === "CANCELLED") return "cancelled";
  if (value === "FAILED") return "failed";
  return "completed";
}

/** Agent SSE 运行状态：聚合用户可读回合与可按需展开的技术轨迹。 */
export function useAgentStream(userId: string) {
  const [turns, setTurns] = useState<ConversationTurn[]>([]);
  const [traceEvents, setTraceEvents] = useState<RunTraceEvent[]>([]);
  const [question, setQuestion] = useState<WorkflowQuestionEvent | null>(null);
  const [intervention, setIntervention] = useState<Intervention | null>(null);
  const [busy, setBusy] = useState(false);
  const [deciding, setDeciding] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);
  const activeRequestIdRef = useRef<string | null>(null);
  const activeTurnIdRef = useRef<string | null>(null);
  const streamSequenceRef = useRef(0);

  const updateActiveTurn = useCallback((update: (turn: ConversationTurn) => ConversationTurn) => {
    const activeTurnId = activeTurnIdRef.current;
    if (!activeTurnId) return;
    setTurns((current) => current.map((turn) => turn.id === activeTurnId ? update(turn) : turn));
  }, []);

  const append = useCallback((type: string, data: unknown) => {
    const at = new Date().toISOString();
    if (TRACE_EVENT_TYPES.has(type as RunTraceEvent["type"])) {
      setTraceEvents((current) => [...current, {
        id: crypto.randomUUID(), type: type as RunTraceEvent["type"], data: traceValue(data), at
      }]);
    }
    if (type === "content") {
      updateActiveTurn((turn) => ({ ...turn, content: `${turn.content}${traceValue(data)}` }));
      return;
    }
    if (type === "route") {
      updateActiveTurn((turn) => ({ ...turn, route: traceValue(data) }));
      return;
    }
    if (type === "result") {
      updateActiveTurn((turn) => ({ ...turn, result: data }));
      return;
    }
    if (type === "workflow_question") {
      setQuestion(data as WorkflowQuestionEvent);
      updateActiveTurn((turn) => ({ ...turn, status: "waiting" }));
      return;
    }
    if (type === "intervention") {
      setIntervention(data as Intervention);
      updateActiveTurn((turn) => ({ ...turn, status: "waiting" }));
      return;
    }
    if (type === "error") {
      updateActiveTurn((turn) => ({ ...turn, error: traceValue(data), status: "failed", finishedAt: at }));
      return;
    }
    if (type === "done") {
      updateActiveTurn((turn) => ({
        ...turn,
        status: completionStatus(data),
        finishedAt: at
      }));
    }
  }, [updateActiveTurn]);

  const startTurn = useCallback((requestIdValue: string, userMessage: string) => {
    const id = crypto.randomUUID();
    const startedAt = new Date().toISOString();
    activeTurnIdRef.current = id;
    setTurns((current) => [...current, {
      id,
      requestId: requestIdValue,
      userMessage,
      content: "",
      result: null,
      error: null,
      route: null,
      status: "running",
      startedAt,
      finishedAt: null
    }]);
  }, []);

  const consume = useCallback(async (path: string, body: StreamRequest | AnswerRequest, userMessage: string) => {
    if (controllerRef.current) {
      controllerRef.current.abort();
      const interruptedAt = new Date().toISOString();
      updateActiveTurn((turn) => ({ ...turn, status: "cancelled", finishedAt: interruptedAt }));
      setTraceEvents((current) => [...current, {
        id: crypto.randomUUID(), type: "progress", data: "已由新请求替换", at: interruptedAt
      }]);
    }
    const controller = new AbortController();
    const sequence = ++streamSequenceRef.current;
    controllerRef.current = controller;
    activeRequestIdRef.current = body.requestId;
    startTurn(body.requestId, userMessage);
    setBusy(true);
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify(body),
        signal: controller.signal
      });
      if (!response.ok || !response.body) {
        if (!response.ok) await readJsonResponse(response);
        throw new HttpRequestError("SSE 响应未建立，请稍后重试。", "network", response.status);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffered = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffered = appendSseChunk(buffered + decoder.decode(value, { stream: true }), append);
      }
      if (buffered.trim()) appendSseChunk(`${buffered}\n\n`, append);
    } catch (error) {
      if (!controller.signal.aborted && sequence === streamSequenceRef.current) {
        append("error", error instanceof Error ? error.message : "Unknown request error");
      }
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null;
        activeRequestIdRef.current = null;
      }
      if (sequence === streamSequenceRef.current) setBusy(false);
    }
  }, [append, startTurn, updateActiveTurn, userId]);

  useEffect(() => () => controllerRef.current?.abort(), []);

  const sendChat = useCallback((sessionId: string, message: string, memory: MemoryOptions) => {
    setQuestion(null);
    setIntervention(null);
    const nextRequestId = requestId("chat");
    return consume(`${API}/chat/stream`, { requestId: nextRequestId, sessionId, message, memory }, message);
  }, [consume]);

  const answer = useCallback((input: Omit<AnswerRequest, "requestId">) => {
    setQuestion(null);
    const nextRequestId = requestId("answer");
    return consume(`${API}/workflow-runs/${encodeURIComponent(input.runId)}/answers/stream`, {
      ...input,
      requestId: nextRequestId
    }, "已提交 Workflow 回答");
  }, [consume]);

  const decide = useCallback(async (sessionId: string, decision: "CONFIRM" | "REJECT") => {
    if (deciding || !intervention || !activeRequestIdRef.current) return;
    setDeciding(true);
    try {
      await requestJson<void>(`${API}/requests/${encodeURIComponent(activeRequestIdRef.current)}/interventions/${encodeURIComponent(intervention.replyId)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ sessionId, toolCallIds: intervention.tools.map((tool) => tool.toolCallId), decision })
      });
      setIntervention(null);
      updateActiveTurn((turn) => ({ ...turn, status: "running" }));
    } catch (error) {
      append("error", error instanceof Error ? error.message : "工具确认提交失败，请重试。");
    } finally {
      setDeciding(false);
    }
  }, [append, deciding, intervention, updateActiveTurn, userId]);

  const cancel = useCallback(async () => {
    const currentRequestId = activeRequestIdRef.current;
    controllerRef.current?.abort();
    if (!currentRequestId) return;
    const finishedAt = new Date().toISOString();
    updateActiveTurn((turn) => ({ ...turn, status: "cancelled", finishedAt }));
    setTraceEvents((current) => [...current, {
      id: crypto.randomUUID(), type: "progress", data: "已请求取消", at: finishedAt
    }]);
    try {
      await requestJson<void>(`${API}/requests/${encodeURIComponent(currentRequestId)}`, {
        method: "DELETE", headers: { "X-User-Id": userId }
      });
    } catch (error) {
      append("error", error instanceof Error ? error.message : "取消请求失败，请稍后重试。");
    }
  }, [append, updateActiveTurn, userId]);

  const clearView = useCallback(() => {
    if (busy) return;
    activeTurnIdRef.current = null;
    setTurns([]);
    setTraceEvents([]);
    setQuestion(null);
    setIntervention(null);
  }, [busy]);

  const timeline: TimelineEvent[] = traceEvents.map((event) => ({
    id: event.id, type: event.type, data: event.data, at: event.at
  }));

  return {
    answer,
    busy,
    cancel,
    clearView,
    decide,
    deciding,
    intervention,
    question,
    sendChat,
    timeline,
    traceEvents,
    turns
  };
}
