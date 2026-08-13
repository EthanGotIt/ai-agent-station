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

  it("does not request the review queue without an operator identity", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    render(<AfterSalesReviewPanel operatorId="  " />);

    await waitFor(() => expect(fetchMock).not.toHaveBeenCalled());
  });

  it("ignores an older queue response after a refresh replaces it", async () => {
    const initial = deferred<Response>();
    const fetchMock = vi.fn()
      .mockReturnValueOnce(initial.promise)
      .mockResolvedValueOnce(json({ items: [{ ...pendingCase, orderId: "ORDER-NEW" }], page: 0, size: 20, hasNext: false }));
    vi.stubGlobal("fetch", fetchMock);

    const view = render(<AfterSalesReviewPanel operatorId="operator-1" />);
    view.rerender(<AfterSalesReviewPanel operatorId="operator-2" />);
    await screen.findByText("ORDER-NEW");
    initial.resolve(json({ items: [{ ...pendingCase, orderId: "ORDER-OLD" }], page: 0, size: 20, hasNext: false }));

    await waitFor(() => expect(screen.queryByText("ORDER-OLD")).toBeNull());
  });

  it("submits only one write for rapid clicks and reuses the idempotency key after an ambiguous failure", async () => {
    const completion = deferred<Response>();
    const randomUUID = vi.fn().mockReturnValue("decision-stable");
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ items: [pendingCase], page: 0, size: 20, hasNext: false }))
      .mockResolvedValueOnce(json(pendingCase))
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockReturnValueOnce(completion.promise);
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID });

    render(<AfterSalesReviewPanel operatorId="operator-1" />);
    await screen.findByText("ORDER-001");
    fireEvent.click(screen.getByRole("button", { name: /ORDER-001/ }));
    await screen.findByRole("button", { name: "批准并创建退款任务" });

    fireEvent.click(screen.getByRole("button", { name: "批准并创建退款任务" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "批准并创建退款任务" }));
    fireEvent.click(screen.getByRole("button", { name: "批准并创建退款任务" }));

    expect(fetchMock).toHaveBeenCalledTimes(4);
    const firstWrite = JSON.parse(fetchMock.mock.calls[2][1].body);
    const secondWrite = JSON.parse(fetchMock.mock.calls[3][1].body);
    expect(firstWrite.decisionId).toBe("decision-stable");
    expect(secondWrite.decisionId).toBe("decision-stable");
    expect(randomUUID).toHaveBeenCalledTimes(1);

    completion.resolve(json({ caseModel: { ...pendingCase, status: "REFUND_PROCESSING", version: 4 } }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "批准并创建退款任务" })).toBeNull());
  });

  it("refreshes the detail after a version conflict", async () => {
    const latest = { ...pendingCase, version: 4, description: "已由其他操作员更新" };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ items: [pendingCase], page: 0, size: 20, hasNext: false }))
      .mockResolvedValueOnce(json(pendingCase))
      .mockResolvedValueOnce(new Response(
        '{"code":"AFTER_SALES_CASE_CONFLICT","message":"状态已变化"}', { status: 409 }
      ))
      .mockResolvedValueOnce(json(latest));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => "decision-1" });

    render(<AfterSalesReviewPanel operatorId="operator-1" />);
    await screen.findByText("ORDER-001");
    fireEvent.click(screen.getByRole("button", { name: /ORDER-001/ }));
    await screen.findByRole("button", { name: "批准并创建退款任务" });
    fireEvent.click(screen.getByRole("button", { name: "批准并创建退款任务" }));

    await screen.findByText("已由其他操作员更新");
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(screen.getByRole("alert").textContent).toContain("已刷新详情");
  });

  it("renders long CJK and emoji identifiers without truncating their content", async () => {
    const longOrderId = `订单🧧-${"退款状态需要人工复核".repeat(12)}`;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      items: [{ ...pendingCase, orderId: longOrderId, description: `${longOrderId}，请尽快处理。` }],
      page: 0, size: 20, hasNext: false
    })));

    render(<AfterSalesReviewPanel operatorId="operator-1" />);

    expect(await screen.findByText(longOrderId)).not.toBeNull();
  });
});

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}
