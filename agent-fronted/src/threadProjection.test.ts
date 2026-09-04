import { describe, expect, it } from "vitest";
import { createOpenInteractionIndex, createThreadProjectionCache, findOpenInteraction, normalizeItem, rebuildTurns } from "./threadProjection";
import { findOrderAction, projectOrderAction } from "./orderActionProjection";
import type { AgentItemWire, AgentItemType } from "./threadTypes";

function item(
  type: AgentItemType,
  sequence: number,
  turnId: string,
  data: unknown
): AgentItemWire {
  return {
    itemId: `${turnId}-${sequence}`,
    turnId,
    sequence,
    type,
    schemaVersion: 1,
    payload: JSON.stringify({ schemaVersion: 1, kind: type, data }),
    createdAt: `2026-08-28T00:00:${String(sequence).padStart(2, "0")}Z`
  };
}

describe("thread projection", () => {
  it("projects the Workflow order shape and decimal strings from the final receipt", () => {
    const sourceTurnId = "turn-source";
    const actionTurnId = "turn-action";
    const items = [
      item("USER_MESSAGE", 1, sourceTurnId, "查询订单"),
      item("ORDER_DETAIL", 2, sourceTurnId, {
        orderId: "ORDER-1",
        status: "PAID",
        paidAmount: "39.90",
        currency: "CNY",
        visibility: "ACTIVE"
      }),
      item("TURN_STATE", 3, sourceTurnId, { status: "COMPLETED" }),
      item("ORDER_ACTION_REQUEST", 4, actionTurnId, {
        sourceTurnId,
        orderId: "ORDER-1",
        actionType: "REFUND"
      }),
      item("EXTERNAL_ACTION_STATUS", 5, actionTurnId, {
        runId: "run-1",
        status: "SUCCEEDED",
        actionType: "REFUND",
        orderId: "ORDER-1"
      }),
      item("ORDER_DETAIL", 6, actionTurnId, {
        orderId: "ORDER-1",
        status: "REFUNDED",
        paidAmount: "39.90",
        currency: "CNY",
        visibility: "ACTIVE"
      })
    ].map(normalizeItem);

    const [turn] = rebuildTurns(items);

    expect(turn.orderCards).toHaveLength(1);
    expect(turn.orderCards[0]).toMatchObject({ status: "REFUNDED", paidAmount: 39.9 });
  });

  it("keeps a direct delete action after rebuilding the source Turn", () => {
    const sourceTurnId = "turn-source";
    const actionTurnId = "turn-delete";
    const items = [
      item("USER_MESSAGE", 1, sourceTurnId, "查看订单"),
      item("ORDER_DETAIL", 2, sourceTurnId, {
        orderId: "ORDER-DELETE-1",
        status: "PAID",
        visibility: "ACTIVE"
      }),
      item("ORDER_ACTION_REQUEST", 3, actionTurnId, {
        sourceTurnId,
        orderId: "ORDER-DELETE-1",
        actionType: "DELETE_ORDER"
      }),
      item("EXTERNAL_ACTION_STATUS", 4, actionTurnId, {
        runId: "run-delete-1",
        status: "SUCCEEDED",
        actionType: "DELETE_ORDER",
        orderId: "ORDER-DELETE-1",
        code: "ORDER_DELETED",
        verificationStatus: "VERIFIED"
      })
    ].map(normalizeItem);

    const [turn] = rebuildTurns(items);
    expect(turn.orderCards).toHaveLength(1);
    expect(turn.orderCards[0].orderId).toBe("ORDER-DELETE-1");
    const request = findOrderAction(turn, "ORDER-DELETE-1");
    expect(request).toMatchObject({
      actionType: "DELETE_ORDER", orderId: "ORDER-DELETE-1"
    });
    expect(projectOrderAction(turn, request!)).toMatchObject({ state: "done", deleted: true });
  });

  it("keeps an Agent QuestionCard visible when its answer fails", () => {
    const items = [
      item("QUESTION_CARD", 1, "owner-turn", {
        questionId: "question-1", runId: null, resumeTarget: "AGENT",
        title: "补充订单号", prompt: "请补充订单号", fields: []
      }),
      item("QUESTION_ANSWER", 2, "answer-turn", {
        questionId: "question-1", runId: null, resumeTarget: "AGENT", action: "SUBMIT"
      }),
      item("TURN_STATE", 3, "answer-turn", { status: "FAILED" }),
      item("WORKFLOW_RESULT", 4, "workflow-turn", { runId: null, status: "CANCELLED" })
    ].map(normalizeItem);

    expect(findOpenInteraction(items)).toMatchObject({
      type: "QUESTION_CARD", question: { questionId: "question-1" }
    });
  });

  it("closes a QuestionCard only after its answer Turn completes", () => {
    const items = [
      item("QUESTION_CARD", 1, "owner-turn", {
        questionId: "question-1", runId: "run-1", resumeTarget: "WORKFLOW",
        title: "补充订单号", prompt: "请补充订单号", fields: []
      }),
      item("QUESTION_ANSWER", 2, "answer-turn", {
        questionId: "question-1", runId: "run-1", resumeTarget: "WORKFLOW", action: "CANCEL"
      }),
      item("WORKFLOW_RESULT", 3, "answer-turn", { runId: "run-1", status: "CANCELLED" }),
      item("TURN_STATE", 4, "answer-turn", { status: "COMPLETED" })
    ].map(normalizeItem);

    expect(findOpenInteraction(items)).toBeNull();
  });

  it("preserves a failed Workflow result after the decision Turn closes", () => {
    const ownerTurnId = "owner-turn";
    const decisionTurnId = "decision-turn";
    const items = [
      item("USER_MESSAGE", 1, ownerTurnId, "申请退款"),
      item("WORKFLOW_CHECKPOINT", 2, ownerTurnId, {
        checkpointId: "checkpoint-1", runId: "run-1", nodeId: "AUTHORIZE",
        actionType: "REFUND", orderId: "ORDER-1", impactSummary: "提交退款",
        factsFingerprint: "facts-1", status: "OPEN", version: 0
      }),
      item("WORKFLOW_DECISION", 3, decisionTurnId, {
        runId: "run-1", checkpointId: "checkpoint-1", expectedVersion: 0,
        decision: "APPROVE", factsFingerprint: "facts-1"
      }),
      item("WORKFLOW_RESULT", 4, decisionTurnId, { runId: "run-1", status: "FAILED" }),
      item("TURN_STATE", 5, decisionTurnId, { status: "COMPLETED" })
    ].map(normalizeItem);

    const [turn] = rebuildTurns(items);

    expect(turn.status).toBe("FAILED");
    expect(turn.activities).toContainEqual(expect.objectContaining({
      label: "售后流程未完成", status: "ERROR"
    }));
  });

  it("keeps a facts-changed Workflow result waiting for a new confirmation", () => {
    const ownerTurnId = "owner-turn";
    const decisionTurnId = "decision-turn";
    const items = [
      item("USER_MESSAGE", 1, ownerTurnId, "申请退款"),
      item("WORKFLOW_CHECKPOINT", 2, ownerTurnId, {
        checkpointId: "checkpoint-1", runId: "run-1", nodeId: "AUTHORIZE",
        actionType: "REFUND", orderId: "ORDER-1", impactSummary: "提交退款",
        factsFingerprint: "facts-1", status: "OPEN", version: 0
      }),
      item("WORKFLOW_DECISION", 3, decisionTurnId, {
        runId: "run-1", checkpointId: "checkpoint-1", expectedVersion: 0,
        decision: "APPROVE", factsFingerprint: "facts-1"
      }),
      item("WORKFLOW_RESULT", 4, decisionTurnId, { runId: "run-1", status: "FACTS_CHANGED" }),
      item("TURN_STATE", 5, decisionTurnId, { status: "COMPLETED" })
    ].map(normalizeItem);

    const [turn] = rebuildTurns(items);

    expect(turn.status).toBe("WAITING_USER_INPUT");
    expect(turn.activities).toContainEqual(expect.objectContaining({
      label: "等待补充信息", status: "WAITING"
    }));
  });

  it("does not mark a failed order action as done when its Turn closes", () => {
    const items = [
      item("ORDER_ACTION_REQUEST", 1, "action-turn", {
        sourceTurnId: "source-turn", orderId: "ORDER-1", actionType: "REFUND"
      }),
      item("WORKFLOW_RESULT", 2, "action-turn", { runId: "run-1", status: "FAILED" }),
      item("TURN_STATE", 3, "action-turn", { status: "COMPLETED" })
    ].map(normalizeItem);
    const [turn] = rebuildTurns(items);
    const request = findOrderAction(turn, "ORDER-1");

    expect(request).not.toBeNull();
    expect(projectOrderAction(turn, request!)).toMatchObject({ state: "error" });
  });

  it("lets external action success close an approved Workflow result", () => {
    const items = [
      item("ORDER_ACTION_REQUEST", 1, "action-turn", {
        sourceTurnId: "source-turn", orderId: "ORDER-1", actionType: "REFUND"
      }),
      item("WORKFLOW_RESULT", 2, "action-turn", { runId: "run-1", status: "APPROVED" }),
      item("EXTERNAL_ACTION_STATUS", 3, "action-turn", {
        runId: "run-1", status: "SUCCEEDED", orderId: "ORDER-1", actionType: "REFUND"
      }),
      item("TURN_STATE", 4, "action-turn", { status: "COMPLETED" })
    ].map(normalizeItem);
    const [turn] = rebuildTurns(items);
    const request = findOrderAction(turn, "ORDER-1");

    expect(turn.status).toBe("COMPLETED");
    expect(projectOrderAction(turn, request!)).toMatchObject({ state: "done" });
  });

  it("does not treat a different folded action failure as a continuation warning", () => {
    const items = [
      item("USER_MESSAGE", 1, "source-turn", "处理订单"),
      item("ORDER_ACTION_REQUEST", 2, "action-a", {
        sourceTurnId: "source-turn", orderId: "ORDER-A", actionType: "REFUND"
      }),
      item("EXTERNAL_ACTION_STATUS", 3, "action-a", {
        runId: "run-a", status: "SUCCEEDED", orderId: "ORDER-A", actionType: "REFUND"
      }),
      item("ORDER_ACTION_REQUEST", 4, "action-b", {
        sourceTurnId: "source-turn", orderId: "ORDER-B", actionType: "EXPEDITE"
      }),
      item("ERROR", 5, "action-b", "催发货失败"),
      item("TURN_STATE", 6, "action-b", { status: "FAILED" })
    ].map(normalizeItem);

    const [turn] = rebuildTurns(items);

    expect(turn.status).toBe("FAILED");
    expect(turn.continuationWarning).toBeNull();
    expect(turn.error).toBe("催发货失败");
  });

  it("projects a missing decision as a retryable failure without an open interaction", () => {
    const items = [
      item("USER_MESSAGE", 1, "turn-missing-decision", "查询订单"),
      item("AGENT_DECISION", 2, "turn-missing-decision", {
        decision: "FINISH", code: "CONTROL_TOOL", correctionAttempt: true
      }),
      item("ERROR", 3, "turn-missing-decision", "AGENT_DECISION_MISSING"),
      item("TURN_STATE", 4, "turn-missing-decision", {
        status: "FAILED", errorCode: "AGENT_DECISION_MISSING"
      })
    ].map(normalizeItem);

    const [turn] = rebuildTurns(items);

    expect(turn.status).toBe("FAILED");
    expect(turn.errorCode).toBe("AGENT_DECISION_MISSING");
    expect(turn.decisions).toContainEqual(expect.objectContaining({
      decision: "FINISH", correctionAttempt: true
    }));
    expect(findOpenInteraction(items)).toBeNull();
  });

  it("reuses unaffected Turn references when appending an Item", () => {
    const initialItems = [
      item("USER_MESSAGE", 1, "turn-a", "查看订单"),
      item("USER_MESSAGE", 2, "turn-b", "查询物流")
    ].map(normalizeItem);
    const cache = createThreadProjectionCache();
    const first = rebuildTurns(initialItems, cache);
    const second = rebuildTurns([
      ...initialItems,
      normalizeItem(item("ASSISTANT_MESSAGE", 3, "turn-b", "已发货"))
    ], cache);

    expect(second.find((turn) => turn.turnId === "turn-a")).toBe(first.find((turn) => turn.turnId === "turn-a"));
    expect(second.find((turn) => turn.turnId === "turn-b")).not.toBe(first.find((turn) => turn.turnId === "turn-b"));
  });

  it("keeps folded Turn projection equivalent to a full rebuild across appended Items", () => {
    const sourceTurnId = "turn-source";
    const actionTurnId = "turn-action";
    const initialItems = [
      item("USER_MESSAGE", 1, sourceTurnId, "申请退款"),
      item("ORDER_DETAIL", 2, sourceTurnId, { orderId: "ORDER-1", status: "PAID", visibility: "ACTIVE" })
    ].map(normalizeItem);
    const appendedItems = [
      item("ORDER_ACTION_REQUEST", 3, actionTurnId, {
        sourceTurnId, orderId: "ORDER-1", actionType: "REFUND"
      }),
      item("WORKFLOW_RESULT", 4, actionTurnId, { runId: "run-1", status: "APPROVED" }),
      item("EXTERNAL_ACTION_STATUS", 5, actionTurnId, {
        runId: "run-1", status: "SUCCEEDED", orderId: "ORDER-1", actionType: "REFUND"
      })
    ].map(normalizeItem);
    const cache = createThreadProjectionCache();
    rebuildTurns(initialItems, cache);
    let current = initialItems;
    for (const nextItem of appendedItems) {
      current = [...current, nextItem];
      expect(rebuildTurns(current, cache)).toEqual(rebuildTurns(current));
    }
    expect(rebuildTurns(current, cache)).toHaveLength(1);
    expect(rebuildTurns(current, cache)[0].externalActionStatus).toBe("SUCCEEDED");
  });

  it("does not duplicate a newly appended Turn when several Items arrive together", () => {
    const initialItems = [item("USER_MESSAGE", 1, "turn-a", "查看订单")].map(normalizeItem);
    const appendedItems = [
      item("USER_MESSAGE", 2, "turn-b", "查询物流"),
      item("ASSISTANT_MESSAGE", 3, "turn-b", "已发货")
    ].map(normalizeItem);
    const cache = createThreadProjectionCache();
    rebuildTurns(initialItems, cache);
    const next = [...initialItems, ...appendedItems];

    expect(rebuildTurns(next, cache)).toEqual(rebuildTurns(next));
    expect(rebuildTurns(next, cache).find((turn) => turn.turnId === "turn-b")?.items).toHaveLength(2);
  });

  it("advances the open interaction index without replaying prior Items", () => {
    const index = createOpenInteractionIndex();
    const steps = [
      [item("QUESTION_CARD", 1, "owner-turn", {
        questionId: "question-1", runId: "run-1", resumeTarget: "WORKFLOW",
        title: "补充订单号", prompt: "请补充订单号", fields: []
      })],
      [item("QUESTION_ANSWER", 2, "answer-turn", {
        questionId: "question-1", runId: "run-1", resumeTarget: "WORKFLOW", action: "SUBMIT"
      })],
      [item("TURN_STATE", 3, "answer-turn", { status: "FAILED" })],
      [item("QUESTION_ANSWER", 4, "answer-turn-2", {
        questionId: "question-1", runId: "run-1", resumeTarget: "WORKFLOW", action: "CANCEL"
      })],
      [item("WORKFLOW_RESULT", 5, "answer-turn-2", { runId: "run-1", status: "CANCELLED" })]
    ];
    let current: ReturnType<typeof normalizeItem>[] = [];
    for (const [nextItem] of steps) {
      current = [...current, normalizeItem(nextItem)];
      expect(findOpenInteraction(current, index)).toEqual(findOpenInteraction(current));
    }
    const outOfOrder = [...current].reverse();
    expect(findOpenInteraction(outOfOrder, index)).toEqual(findOpenInteraction(outOfOrder));
  });
});
