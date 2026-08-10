import { useCallback, useRef, useState } from "react";
import { appendSseChunk } from "./sse";
import type { Intervention, MemoryOptions, TimelineEvent, WorkflowQuestionEvent } from "./types";

const API = "/api/v1/agent";

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

export function useAgentStream(userId: string) {
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [question, setQuestion] = useState<WorkflowQuestionEvent | null>(null);
  const [intervention, setIntervention] = useState<Intervention | null>(null);
  const [busy, setBusy] = useState(false);
  const [deciding, setDeciding] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);
  const activeRequestIdRef = useRef<string | null>(null);

  const append = useCallback((type: string, data: unknown) => {
    setTimeline((current) => [...current, { id: crypto.randomUUID(), type, data, at: new Date().toISOString() }]);
    if (type === "workflow_question") setQuestion(data as WorkflowQuestionEvent);
    if (type === "intervention") setIntervention(data as Intervention);
    if (type === "result") setQuestion(null);
  }, []);

  const consume = useCallback(async (path: string, body: StreamRequest | AnswerRequest) => {
    const controller = new AbortController();
    controllerRef.current = controller;
    activeRequestIdRef.current = body.requestId;
    setBusy(true);
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify(body),
        signal: controller.signal
      });
      if (!response.ok || !response.body) {
        const error = await response.json().catch(() => ({})) as { code?: string; message?: string };
        throw new Error(error.message ?? error.code ?? `Request failed: ${response.status}`);
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
      if (!(error instanceof DOMException && error.name === "AbortError")) {
        append("error", error instanceof Error ? error.message : "Unknown request error");
      }
    } finally {
      controllerRef.current = null;
      activeRequestIdRef.current = null;
      setBusy(false);
    }
  }, [append, userId]);

  const sendChat = useCallback((sessionId: string, message: string, memory: MemoryOptions) => {
    setQuestion(null);
    setIntervention(null);
    return consume(`${API}/chat/stream`, { requestId: requestId("chat"), sessionId, message, memory });
  }, [consume]);

  const answer = useCallback((input: Omit<AnswerRequest, "requestId">) => {
    return consume(`${API}/workflow-runs/${encodeURIComponent(input.runId)}/answers/stream`, {
      ...input,
      requestId: requestId("answer")
    });
  }, [consume]);

  const decide = useCallback(async (sessionId: string, decision: "CONFIRM" | "REJECT") => {
    if (deciding || !intervention || !activeRequestIdRef.current) return;
    setDeciding(true);
    try {
      const response = await fetch(`${API}/requests/${encodeURIComponent(activeRequestIdRef.current)}/interventions/${encodeURIComponent(intervention.replyId)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ sessionId, toolCallIds: intervention.tools.map((tool) => tool.toolCallId), decision })
      });
      if (!response.ok) append("error", `Intervention decision failed: ${response.status}`);
      else setIntervention(null);
    } finally {
      setDeciding(false);
    }
  }, [append, deciding, intervention, userId]);

  const cancel = useCallback(async () => {
    const currentRequestId = activeRequestIdRef.current;
    controllerRef.current?.abort();
    if (currentRequestId) await fetch(`${API}/requests/${encodeURIComponent(currentRequestId)}`, {
      method: "DELETE", headers: { "X-User-Id": userId }
    });
  }, [userId]);

  return { answer, busy, cancel, decide, deciding, intervention, question, sendChat, timeline };
}
