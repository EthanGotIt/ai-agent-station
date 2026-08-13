import { useCallback, useEffect, useRef, useState } from "react";
import { HttpRequestError, readJsonResponse, requestJson } from "./http";
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
  const streamSequenceRef = useRef(0);

  const append = useCallback((type: string, data: unknown) => {
    setTimeline((current) => [...current, { id: crypto.randomUUID(), type, data, at: new Date().toISOString() }]);
    if (type === "workflow_question") setQuestion(data as WorkflowQuestionEvent);
    if (type === "intervention") setIntervention(data as Intervention);
    if (type === "result") setQuestion(null);
  }, []);

  const consume = useCallback(async (path: string, body: StreamRequest | AnswerRequest) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    const sequence = ++streamSequenceRef.current;
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
  }, [append, userId]);

  useEffect(() => () => controllerRef.current?.abort(), []);

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
      await requestJson<void>(`${API}/requests/${encodeURIComponent(activeRequestIdRef.current)}/interventions/${encodeURIComponent(intervention.replyId)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ sessionId, toolCallIds: intervention.tools.map((tool) => tool.toolCallId), decision })
      });
      setIntervention(null);
    } catch (error) {
      append("error", error instanceof Error ? error.message : "工具确认提交失败，请重试。");
    } finally {
      setDeciding(false);
    }
  }, [append, deciding, intervention, userId]);

  const cancel = useCallback(async () => {
    const currentRequestId = activeRequestIdRef.current;
    controllerRef.current?.abort();
    if (!currentRequestId) return;
    try {
      await requestJson<void>(`${API}/requests/${encodeURIComponent(currentRequestId)}`, {
        method: "DELETE", headers: { "X-User-Id": userId }
      });
    } catch (error) {
      append("error", error instanceof Error ? error.message : "取消请求失败，请稍后重试。");
    }
  }, [append, userId]);

  return { answer, busy, cancel, decide, deciding, intervention, question, sendChat, timeline };
}
