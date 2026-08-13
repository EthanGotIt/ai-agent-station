import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AfterSalesReviewPanel } from "./AfterSalesReviewPanel";

const pendingCase = {
  caseId: "CASE-001", workflowRunId: "RUN-001", userId: "demo-user-1", orderId: "ORDER-001",
  reason: "NOT_RECEIVED", description: "物流未送达", handlingMode: "MANUAL_REVIEW", status: "PENDING_REVIEW",
  amount: 99, currency: "CNY", refundId: "", operatorId: "", decisionId: "", decisionNote: "",
  reviewedAt: null, failureCode: "", version: 3, createdAt: "2026-08-12T12:00:00Z",
  updatedAt: "2026-08-12T12:00:00Z", refundCommand: null
} as const;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("AfterSalesReviewPanel", () => {
  it("loads an operator-scoped case and submits an optimistic-lock protected approval", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ items: [pendingCase], page: 0, size: 20, hasNext: false }))
      .mockResolvedValueOnce(json(pendingCase))
      .mockResolvedValueOnce(json({
        caseModel: {
          ...pendingCase,
          status: "REFUND_PROCESSING",
          operatorId: "operator-1",
          decisionId: "decision-1",
          refundId: "REFUND-001",
          version: 4,
          refundCommand: {
            refundId: "REFUND-001", workflowRunId: "RUN-001", status: "PENDING", amount: 99,
            currency: "CNY", retryId: "", attemptCount: 0, nextAttemptAt: "2026-08-12T12:00:00Z",
            leaseUntil: null, failureCode: "", version: 0, createdAt: "2026-08-12T12:00:00Z",
            updatedAt: "2026-08-12T12:00:00Z"
          }
        }
      }));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => "decision-1" });

    render(<AfterSalesReviewPanel operatorId="operator-1" />);

    await screen.findByText("ORDER-001");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/after-sales/cases?page=0&size=20");
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ "X-Operator-Id": "operator-1" });

    fireEvent.click(screen.getByRole("button", { name: /ORDER-001/ }));
    await screen.findByRole("button", { name: "批准并创建退款任务" });
    fireEvent.click(screen.getByRole("button", { name: "批准并创建退款任务" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(fetchMock.mock.calls[2][0]).toBe("/api/v1/after-sales/cases/CASE-001/review-decisions");
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toMatchObject({
      decisionId: "decision-1", expectedVersion: 3, decision: "APPROVE"
    });
    await waitFor(() => expect(screen.queryByRole("button", { name: "批准并创建退款任务" })).toBeNull());
  });

  it("keeps rejection unavailable until an explanation is supplied", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ items: [pendingCase], page: 0, size: 20, hasNext: false }))
      .mockResolvedValueOnce(json(pendingCase))
    );

    render(<AfterSalesReviewPanel operatorId="operator-1" />);
    await screen.findByText("ORDER-001");
    fireEvent.click(screen.getByRole("button", { name: /ORDER-001/ }));

    expect((await screen.findByRole("button", { name: "驳回申请" })).hasAttribute("disabled")).toBe(true);
  });
});

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}
