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

    fireEvent.click(screen.getByRole("button", { name: "刷新" }));
    await waitFor(() => expect(requestSignal).toBeDefined());
    view.unmount();

    expect(requestSignal?.aborted).toBe(true);
  });

  it("does not submit duplicate memory creation while the first write is pending", () => {
    const fetchMock = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal("fetch", fetchMock);
    render(<MemoryPanel userId="user-1" sessionId="session-1" disabled={false} />);

    fireEvent.change(screen.getByPlaceholderText("受控格式的值"), { target: { value: "en-US" } });
    const create = screen.getByRole("button", { name: "创建" });
    fireEvent.click(create);
    fireEvent.click(create);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
