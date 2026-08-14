import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "#/");
});

describe("Agent Workbench 导航与场景", () => {
  it("uses hash navigation for a directly linked workspace", async () => {
    window.history.replaceState(null, "", "#/memory");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([])));

    render(<App />);

    expect(await screen.findByRole("heading", { name: "可审计会话记忆" })).not.toBeNull();
    expect(screen.getAllByRole("button", { name: /会话记忆/ }).some((button) => button.getAttribute("aria-current") === "page")).toBe(true);
  });

  it("runs a real demo scenario through the existing stream endpoint", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(streamResponse([
      "event: route", "data: ORDER_QUERY", "",
      "event: content", "data: 订单已支付。", "",
      "event: done", "data: COMPLETED", "", ""
    ])));
    render(<App />);

    fireEvent.click(screen.getByRole("button", { name: /查询订单/ }));

    expect(await screen.findByText("订单已支付。")).not.toBeNull();
    await waitFor(() => expect(screen.getByText("已选择 ORDER_QUERY 路径")).not.toBeNull());
  });
});

function json(value: unknown) {
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
}

function streamResponse(lines: string[]) {
  const encoder = new TextEncoder();
  return {
    ok: true,
    body: new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(lines.join("\n")));
        controller.close();
      }
    })
  } as Response;
}
