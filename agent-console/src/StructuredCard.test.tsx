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
});
