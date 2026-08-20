import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  it("QuestionCard 使用后端接受的 APPROVE 决定并提交结构化答案", async () => {
    const thread = threadRecord("thread-1", "退款确认 Thread");
    const questionPayload = JSON.stringify({
      schemaVersion: 1,
      kind: "WORKFLOW_QUESTION",
      data: {
        runId: "run-1",
        questionId: "question-1",
        checkpointId: "checkpoint-1",
        version: 2,
        title: "退款确认",
        prompt: "是否继续退款？",
        fields: [{ name: "decision", label: "决定", type: "select", required: true, options: ["APPROVE", "REJECT"] }]
      }
    });
    const questionEvent = `event: item.workflow_question\ndata: ${JSON.stringify({
      eventId: "item-question-1",
      threadId: "thread-1",
      turnId: "turn-1",
      itemId: "item-question-1",
      type: "item.workflow_question",
      payload: questionPayload,
      sequence: 1,
      timestamp: thread.createdAt
    })}\n\n`;
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse([questionEvent]));
      if (url.includes("/workflow-runs/run-1/questions/question-1/answers")) {
        return Promise.resolve(json({ turnId: "answer-turn-1" }));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("heading", { name: "退款确认" })).not.toBeNull();
    const decision = screen.getByLabelText("决定") as HTMLSelectElement;
    expect(decision.value).toBe("APPROVE");
    fireEvent.submit(decision.closest("form") as HTMLFormElement);

    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) =>
      String(input).includes("/workflow-runs/run-1/questions/question-1/answers"))).toBe(true));
    const answerCall = fetchMock.mock.calls.find(([input]) =>
      String(input).includes("/workflow-runs/run-1/questions/question-1/answers"));
    expect(JSON.parse(String(answerCall?.[1]?.body)).answers).toEqual({ decision: "APPROVE" });
  });

  it("线程切换后忽略迟到的旧 Thread 历史", async () => {
    const threadOne = threadRecord("thread-1", "Thread 1");
    const threadTwo = threadRecord("thread-2", "Thread 2");
    const delayedHistory = deferred<Response>();
    const staleItem = {
      itemId: "stale-item",
      turnId: "stale-turn",
      sequence: 1,
      type: "USER_MESSAGE",
      schemaVersion: 1,
      payload: JSON.stringify({ schemaVersion: 1, kind: "USER_MESSAGE", data: "stale old" }),
      createdAt: threadOne.createdAt
    };
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [threadOne, threadTwo], page: 0, size: 100, total: 2 }));
      }
      if (url.includes("/threads/thread-1/items")) return delayedHistory.promise;
      if (url.includes("/threads/thread-2/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-2/events")) return Promise.resolve(streamResponse([]));
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /Thread 2/ }));
    expect(await screen.findByRole("heading", { name: "从一次清晰的请求开始" })).not.toBeNull();

    delayedHistory.resolve(json({ items: [staleItem], afterSequence: 0, nextAfterSequence: 1, hasMore: false }));
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    expect(screen.queryByText("stale old")).toBeNull();
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

function threadRecord(threadId: string, title: string) {
  const timestamp = new Date().toISOString();
  return { threadId, title, status: "ACTIVE", contextType: null, contextId: null,
    nextSequence: 0, createdAt: timestamp, updatedAt: timestamp };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}
