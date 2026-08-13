import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useAgentStream } from "./useAgentStream";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

/**
 * SSE Hook 测试：取消的 HTTP 边界失败必须变为可见时间线错误而非未处理 Promise。
 */
describe("useAgentStream", () => {
  it("reports a failed cancellation after aborting the active stream", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, options?: RequestInit) => {
      if (String(input).includes("/chat/stream")) {
        return new Promise<Response>((_resolve, reject) => {
          options?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        });
      }
      return Promise.reject(new TypeError("offline"));
    }));
    render(<StreamProbe />);

    fireEvent.click(screen.getByRole("button", { name: "开始" }));
    fireEvent.click(screen.getByRole("button", { name: "取消" }));

    expect(await screen.findByText("网络连接暂时不可用，请检查网络后重试。")).not.toBeNull();
  });
});

function StreamProbe() {
  const agent = useAgentStream("user-1");
  return <>
    <button type="button" onClick={() => void agent.sendChat("session-1", "测试", {})}>开始</button>
    <button type="button" onClick={() => void agent.cancel()}>取消</button>
    <div>{agent.timeline.map((event) => <p key={event.id}>{String(event.data)}</p>)}</div>
  </>;
}
