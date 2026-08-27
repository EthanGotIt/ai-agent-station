import { requestJson } from "./http";
import type {
  AgentItemPage,
  AgentItemWire,
  AgentThread,
  AgentThreadInteractionDto,
  AgentThreadPage,
  OrderActionType,
  QuestionAnswerAction
} from "./threadTypes";

type AgentTurnAccepted = { turnId: string };

const API = "/api/agent";

function userHeaders(userId: string, json = false): HeadersInit {
  return json
    ? { "Content-Type": "application/json", "X-User-Id": userId }
    : { "X-User-Id": userId };
}

/** 工作台 HTTP 边界：集中协议路径和请求体，Hook 只负责生命周期与状态。 */
export const threadWorkspaceApi = {
  listThreads(userId: string, status?: "ACTIVE" | "ARCHIVED") {
    const suffix = status ? `&status=${status}` : "";
    return requestJson<AgentThreadPage>(`${API}/threads?page=0&size=100${suffix}`, {
      headers: userHeaders(userId)
    });
  },

  createThread(userId: string) {
    return requestJson<AgentThread>(`${API}/threads`, {
      method: "POST",
      headers: userHeaders(userId, true),
      body: JSON.stringify({ title: "新的 Agent Thread" })
    });
  },

  listItems(userId: string, threadId: string, afterSequence: number, signal?: AbortSignal) {
    return requestJson<AgentItemPage>(
      `${API}/threads/${encodeURIComponent(threadId)}/items?afterSequence=${afterSequence}&limit=500`,
      { headers: userHeaders(userId), signal }
    );
  },

  getInteraction(userId: string, threadId: string, signal?: AbortSignal) {
    return requestJson<AgentThreadInteractionDto | undefined>(
      `${API}/threads/${encodeURIComponent(threadId)}/interaction`,
      { headers: userHeaders(userId), signal }
    );
  },

  submitMessage(userId: string, threadId: string, clientRequestId: string, message: string) {
    return requestJson<AgentTurnAccepted>(`${API}/threads/${encodeURIComponent(threadId)}/turns`, {
      method: "POST",
      headers: userHeaders(userId, true),
      body: JSON.stringify({ clientRequestId, message })
    });
  },

  submitOrderAction(
    userId: string,
    threadId: string,
    clientRequestId: string,
    sourceTurnId: string,
    orderId: string,
    actionType: OrderActionType
  ) {
    return requestJson<AgentTurnAccepted>(`${API}/threads/${encodeURIComponent(threadId)}/order-actions`, {
      method: "POST",
      headers: userHeaders(userId, true),
      body: JSON.stringify({ clientRequestId, sourceTurnId, orderId, actionType })
    });
  },

  submitQuestionAnswer(
    userId: string,
    questionId: string,
    clientRequestId: string,
    expectedVersion: number,
    action: QuestionAnswerAction,
    answers: Record<string, string>
  ) {
    return requestJson<AgentTurnAccepted>(
      `${API}/questions/${encodeURIComponent(questionId)}/answers`,
      {
        method: "POST",
        headers: userHeaders(userId, true),
        body: JSON.stringify({ clientRequestId, expectedVersion, action, answers })
      }
    );
  },

  decideWorkflowCheckpoint(
    userId: string,
    runId: string,
    checkpointId: string,
    clientRequestId: string,
    expectedVersion: number,
    decision: "APPROVE" | "REJECT",
    factsFingerprint: string
  ) {
    return requestJson<AgentTurnAccepted>(
      `${API}/workflow-runs/${encodeURIComponent(runId)}/checkpoints/${encodeURIComponent(checkpointId)}/decisions`,
      {
        method: "POST",
        headers: userHeaders(userId, true),
        body: JSON.stringify({ clientRequestId, expectedVersion, decision, factsFingerprint })
      }
    );
  },

  cancelTurn(userId: string, turnId: string) {
    return requestJson<void>(`${API}/turns/${encodeURIComponent(turnId)}/cancel`, {
      method: "POST",
      headers: userHeaders(userId)
    });
  },

  retryWorkflow(userId: string, runId: string) {
    return requestJson<void>(`${API}/workflow-runs/${encodeURIComponent(runId)}/retry`, {
      method: "POST",
      headers: userHeaders(userId)
    });
  },

  loadExecution(userId: string, turnId: string) {
    return requestJson<{ timeline?: AgentItemWire[] }>(
      `${API}/turns/${encodeURIComponent(turnId)}/execution`,
      { headers: userHeaders(userId) }
    );
  },

  updateThread(userId: string, threadId: string, title: string, archive: boolean) {
    return requestJson<AgentThread>(`${API}/threads/${encodeURIComponent(threadId)}`, {
      method: "PATCH",
      headers: userHeaders(userId, true),
      body: JSON.stringify({ title, archive })
    });
  }
};
