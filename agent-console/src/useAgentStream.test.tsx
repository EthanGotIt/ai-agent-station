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

  it("aggregates content fragments into one conversation turn and separates execution traces", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(streamResponse([
      "event: route", "data: ORDER_QUERY", "",
      "event: content", "data: 第一段", "",
      "event: content", "data: 第二段", "",
      "event: progress", "data: 正在查询", "",
      "event: done", "data: COMPLETED", "", ""
    ])));
    render(<StreamProbe />);

    fireEvent.click(screen.getByRole("button", { name: "开始" }));

    expect(await screen.findByText("第一段第二段")).not.toBeNull();
    expect(screen.getByTestId("trace").textContent).toBe("route:ORDER_QUERY|progress:正在查询|done:COMPLETED");
    expect(screen.getByTestId("turn-count").textContent).toBe("1");
  });
});

function StreamProbe() {
  const agent = useAgentStream("user-1");
  return <>
    <button type="button" onClick={() => void agent.sendChat("session-1", "测试", {})}>开始</button>
    <button type="button" onClick={() => void agent.cancel()}>取消</button>
    <div>{agent.timeline.map((event) => <p key={event.id}>{String(event.data)}</p>)}</div>
    <p data-testid="turn-count">{agent.turns.length}</p>
    <p data-testid="turn-content">{agent.turns.map((turn) => turn.content).join("|")}</p>
    <p data-testid="trace">{agent.traceEvents.map((event) => event.type + ":" + event.data).join("|")}</p>
  </>;
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
