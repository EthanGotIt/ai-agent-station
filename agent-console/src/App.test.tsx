import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Commerce Guardian Agent Thread 工作区", () => {
  it("从唯一的 /api/agent 契约创建并恢复 Thread", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ items: [], page: 0, size: 100, total: 0 }))
      .mockResolvedValueOnce(json({ threadId: "thread-1", title: "新的 Agent Thread", status: "ACTIVE", contextType: null, contextId: null, nextSequence: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }))
      .mockResolvedValueOnce(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }))
      .mockResolvedValueOnce(streamResponse([]));
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("heading", { name: "从一次清晰的请求开始" })).not.toBeNull();
    expect(fetchMock.mock.calls.every(([input]) => String(input).startsWith("/api/agent/"))).toBe(true);
    expect(fetchMock.mock.calls.every(([input]) => !String(input).includes("legacy"))).toBe(true);
  });

  it("显示服务端不可用错误，不生成本地伪回复", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("服务不可用")));
    render(<App />);
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("网络连接暂时不可用"));
    expect(screen.queryByText(/已选择 .* 路径/)).toBeNull();
  });
});

function json(value: unknown) {
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
}

function streamResponse(lines: string[]) {
  const encoder = new TextEncoder();
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      if (lines.length > 0) controller.enqueue(encoder.encode(lines.join("\n")));
      controller.close();
    }
  }));
}
