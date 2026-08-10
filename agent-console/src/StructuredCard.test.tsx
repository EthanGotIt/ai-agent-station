import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StructuredCard } from "./StructuredCard";

describe("StructuredCard", () => {
  it("renders a logistics timeline instead of raw JSON", () => {
    render(<StructuredCard data={{
      cardType: "logistics_timeline",
      data: {
        orderId: "ORDER-001",
        events: [{ status: "IN_TRANSIT", occurredAt: "2026-08-10T10:00:00Z", location: "上海", description: "运输中" }]
      }
    }} />);

    expect(screen.getByText("ORDER-001")).toBeTruthy();
    expect(screen.getByText("IN_TRANSIT")).toBeTruthy();
    expect(screen.getByText("上海 · 运输中")).toBeTruthy();
  });

  it("uses the field names emitted by order and diagnosis workflows", () => {
    const { rerender } = render(<StructuredCard data={{
      cardType: "order_overview",
      data: {
        orderId: "ORDER-001", status: "PAID", paidAmount: "99.00", currency: "CNY",
        items: [{ productName: "演示耳机", quantity: 1, unitPrice: "99.00" }]
      }
    }} />);

    expect(screen.getAllByText("99.00").length).toBeGreaterThan(0);
    expect(screen.getByText("演示耳机 × 1 · 99.00")).toBeTruthy();

    rerender(<StructuredCard data={{
      cardType: "order_diagnosis",
      data: {
        orderId: "ORDER-001", issueType: "LOGISTICS_STALLED", diagnosisType: "LOGISTICS_STALLED",
        recommendation: "建议提供物流停滞信息并等待进一步处理。"
      }
    }} />);

    expect(screen.getByText("建议提供物流停滞信息并等待进一步处理。")).toBeTruthy();
  });
});
