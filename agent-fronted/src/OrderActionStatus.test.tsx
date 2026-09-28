import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OrderActionStatus } from "./OrderActionStatus";
import { findOrderAction, projectOrderAction } from "./orderActionProjection";
import { normalizeItem, rebuildTurns } from "./threadProjection";
import type { AgentItemWire, AgentItemType } from "./threadTypes";

afterEach(() => {
  cleanup();
});

function item(type: AgentItemType, sequence: number, data: unknown): AgentItemWire {
  return {
    itemId: "action-" + sequence,
    turnId: "action-turn",
    sequence,
    type,
    schemaVersion: 1,
    payload: JSON.stringify({ schemaVersion: 1, kind: type, data }),
    createdAt: "2026-09-10T00:00:" + String(sequence).padStart(2, "0") + "Z"
  };
}

function actionView(...additionalItems: AgentItemWire[]) {
  const [turn] = rebuildTurns([
    item("ORDER_ACTION_REQUEST", 1, {
      sourceTurnId: "source-turn",
      orderId: "ORDER-1",
      actionType: "REFUND"
    }),
    ...additionalItems
  ].map(normalizeItem));
  const request = findOrderAction(turn, "ORDER-1");
  if (!request) throw new Error("expected an order action");
  return projectOrderAction(turn, request);
}

function renderStatus(view: ReturnType<typeof actionView>) {
  const onRetry = vi.fn();
  const onRefresh = vi.fn();
  render(<OrderActionStatus
    view={view}
    disabled={false}
    retrying={false}
    onRetry={onRetry}
    onRefresh={onRefresh}
  />);
  return { onRetry, onRefresh };
}

describe("OrderActionStatus", () => {
  it("区分等待确认", () => {
    const view = actionView(item("WORKFLOW_CHECKPOINT", 2, {
      checkpointId: "checkpoint-1",
      runId: "run-1",
      nodeId: "AUTHORIZE",
      actionType: "REFUND",
      orderId: "ORDER-1",
      impactSummary: "提交退款",
      factsFingerprint: "facts-1",
      version: 0
    }));

    expect(view.state).toBe("waiting");
    renderStatus(view);
    expect(screen.getByText("等待你确认订单操作")).not.toBeNull();
  });

  it.each([
    ["PENDING", "订单操作已受理，等待执行"],
    ["PROCESSING", "订单操作正在提交"],
    ["RETRY_WAIT", "外部订单系统暂未完成，系统将自动重试"]
  ] as const)("区分外部动作状态 %s", (status, copy) => {
    const view = actionView(item("EXTERNAL_ACTION_STATUS", 2, {
      runId: "run-1",
      status,
      orderId: "ORDER-1",
      actionType: "REFUND"
    }));

    expect(view.state).toBe("active");
    expect(view.externalActionStatus).toBe(status);
    renderStatus(view);
    expect(screen.getByText(copy)).not.toBeNull();
  });

  it("显示成功并区分成功但尚未核验", () => {
    const view = actionView(item("EXTERNAL_ACTION_STATUS", 2, {
      runId: "run-1",
      status: "SUCCEEDED",
      orderId: "ORDER-1",
      actionType: "REFUND",
      verificationStatus: "PENDING"
    }));

    expect(view.state).toBe("done");
    const { onRefresh } = renderStatus(view);
    expect(screen.getByText("订单操作已提交，等待结果核验")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "重新查询" }));
    expect(onRefresh).toHaveBeenCalledWith("ORDER-1");
  });

  it("显示成功动作的最终回执", () => {
    const view = actionView(item("EXTERNAL_ACTION_STATUS", 2, {
      runId: "run-1",
      status: "SUCCEEDED",
      orderId: "ORDER-1",
      actionType: "REFUND",
      verificationStatus: "VERIFIED",
      verificationMessage: "最新订单状态已核验"
    }));

    renderStatus(view);
    expect(screen.getByText("最新订单状态已核验")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "人工重试" })).toBeNull();
  });

  it("显示人工重试并复用原 Run", () => {
    const view = actionView(item("EXTERNAL_ACTION_STATUS", 2, {
      runId: "run-1",
      status: "MANUAL_RETRY_REQUIRED",
      orderId: "ORDER-1",
      actionType: "REFUND",
      attemptCount: 3,
      maxAttempts: 3
    }));

    expect(view.state).toBe("error");
    expect(view.retryable).toBe(true);
    const { onRetry } = renderStatus(view);
    expect(screen.getByText("订单操作未完成，自动重试已用尽；可以人工重试")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "人工重试" }));
    expect(onRetry).toHaveBeenCalledWith("run-1");
  });

  it("未知结果进入待核实态，人工重放复用原 Run", () => {
    const view = actionView(item("EXTERNAL_ACTION_STATUS", 2, {
      runId: "run-unknown",
      status: "MANUAL_VERIFICATION_REQUIRED",
      outcomeStatus: "UNKNOWN",
      orderId: "ORDER-1",
      actionType: "REFUND",
      verificationAttemptCount: 3,
      maxVerificationAttempts: 3
    }));

    expect(view.state).toBe("waiting");
    expect(view.retryable).toBe(true);
    const { onRetry } = renderStatus(view);
    expect(screen.getByText("订单服务结果待核实，自动核验已用尽；可使用原幂等键重试核验")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "重试结果核验" }));
    expect(onRetry).toHaveBeenCalledWith("run-unknown");
  });

  it("外部动作成功后，续接失败不覆盖业务成功", () => {
    const view = actionView(
      item("EXTERNAL_ACTION_STATUS", 2, {
        runId: "run-1",
        status: "SUCCEEDED",
        orderId: "ORDER-1",
        actionType: "REFUND"
      }),
      item("ERROR", 3, "CONTINUATION_FAILED"),
      item("TURN_STATE", 4, { status: "FAILED", errorCode: "CONTINUATION_FAILED" })
    );

    expect(view.state).toBe("done");
    expect(view.externalActionStatus).toBe("SUCCEEDED");
    renderStatus(view);
    expect(screen.getByText("订单操作已完成")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "人工重试" })).toBeNull();
  });
});
