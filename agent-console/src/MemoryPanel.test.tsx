import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryPanel } from "./MemoryPanel";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

/**
 * 会话记忆面板测试：验证非流式请求在卸载和快速重复操作下保持可预测。
 */
describe("MemoryPanel", () => {
  it("aborts an in-flight refresh on unmount", async () => {
    let requestSignal: AbortSignal | undefined;
    vi.stubGlobal("fetch", vi.fn((_input: RequestInfo | URL, options?: RequestInit) => {
      requestSignal = options?.signal ?? undefined;
      return new Promise<Response>(() => undefined);
    }));
    const view = render(<MemoryPanel userId="user-1" sessionId="session-1" disabled={false} />);

    await waitFor(() => expect(requestSignal).toBeDefined());
    view.unmount();

    expect(requestSignal?.aborted).toBe(true);
  });

  it("does not submit duplicate memory creation while the first write is pending", async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, options?: RequestInit) => {
      if (options?.method === "POST") return new Promise<Response>(() => undefined);
      return Promise.resolve(json([]));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<MemoryPanel userId="user-1" sessionId="session-1" disabled={false} />);

    await screen.findByText("这个会话还没有可用记忆。");
    fireEvent.click(screen.getByRole("button", { name: "创建记忆" }));
    fireEvent.change(screen.getByPlaceholderText("受控格式的值"), { target: { value: "en-US" } });
    const create = screen.getByRole("button", { name: "保存" });
    fireEvent.click(create);
    fireEvent.click(create);

    expect(fetchMock.mock.calls.filter(([, options]) => options?.method === "POST")).toHaveLength(1);
  });

  it("edits a selected memory inline and keeps its evidence with the same detail", async () => {
    const entry = {
      entryId: "MEMORY-1", sourceId: "REQUEST-1", sessionId: "session-1", category: "PREFERENCE" as const,
      memoryKey: "response.language", value: "zh-CN", origin: "MANUAL" as const, confidence: 1,
      version: 2, deleted: false, expiresAt: null, createdAt: "2026-08-13T00:00:00Z", updatedAt: "2026-08-13T00:00:00Z"
    };
    const fetchMock = vi.fn((input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      if (url.includes("/evidence")) return Promise.resolve(json([{
        evidenceId: "EVIDENCE-1", entryId: "MEMORY-1", evidenceType: "MANUAL", evidenceRef: "REQUEST-1",
        createdAt: "2026-08-13T00:00:00Z"
      }]));
      if (options?.method === "PUT") return Promise.resolve(json({ ...entry, value: "en-US", version: 3 }));
      return Promise.resolve(json([{ ...entry, value: "en-US", version: 3 }]));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<MemoryPanel userId="user-1" sessionId="session-1" disabled={false} />);

    await screen.findByRole("heading", { name: "response.language" });
    fireEvent.click(screen.getByRole("button", { name: "编辑" }));
    fireEvent.change(screen.getByRole("textbox", { name: "记忆值" }), { target: { value: "en-US" } });
    fireEvent.click(screen.getByRole("button", { name: "保存修改" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(true));
    const put = fetchMock.mock.calls.find(([, options]) => options?.method === "PUT");
    expect(JSON.parse(String(put?.[1]?.body))).toMatchObject({ value: "en-US", expectedVersion: 3 });

    fireEvent.click(screen.getByRole("button", { name: "查看证据" }));
    expect(await screen.findByRole("heading", { name: "证据" })).not.toBeNull();
  });
});

function json(value: unknown) {
  return new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });
}
