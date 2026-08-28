import { describe, expect, it } from "vitest";
import { normalizeItem, rebuildTurns } from "./threadProjection";
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
});
