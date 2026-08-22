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

    expect(await screen.findByRole("heading", { name: "从一个售后问题开始" })).not.toBeNull();
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

  it("composer 使用 Enter 发送、Shift+Enter 换行并保护中文输入法组合态", async () => {
    const thread = threadRecord("thread-1", "键盘交互 Thread");
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse([]));
      if (url.includes("/threads/thread-1/turns")) {
        return Promise.resolve(json({ turnId: "turn-keyboard-1" }));
      }
      throw new Error(`unexpected request: ${url} ${String(init?.body ?? "")}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    const composer = await screen.findByRole("textbox", { name: "输入请求" }) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: "第一行" } });
    fireEvent.keyDown(composer, { key: "Enter", shiftKey: true });
    expect(composer.value).toBe("第一行");
    fireEvent.change(composer, { target: { value: "第一行\n第二行" } });
    fireEvent.keyDown(composer, { key: "Enter", shiftKey: true });
    expect(composer.value).toBe("第一行\n第二行");

    fireEvent.change(composer, { target: { value: "中文输入" } });
    fireEvent.compositionStart(composer);
    fireEvent.keyDown(composer, { key: "Enter", isComposing: true });
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes("/turns"))).toBe(false);
    fireEvent.compositionEnd(composer);
    fireEvent.keyDown(composer, { key: "Enter" });

    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => String(input).includes("/turns"))).toBe(true));
    const turnCall = fetchMock.mock.calls.find(([input]) => String(input).includes("/turns"));
    expect(JSON.parse(String(turnCall?.[1]?.body)).message).toBe("中文输入");
  });

  it("动态 QuestionCard 接管输入区，支持三选项、其他自定义值、摘要和受限 Markdown", async () => {
    const thread = threadRecord("thread-1", "退款确认 Thread");
    const questionEvent = itemEvent("item-question-2", "thread-1", "turn-1", "WORKFLOW_QUESTION", 1, {
      schemaVersion: 1,
      kind: "WORKFLOW_QUESTION",
      data: {
        runId: "run-2",
        questionId: "question-2",
        checkpointId: "checkpoint-2",
        version: 3,
        title: "确认退款原因",
        prompt: "请确认 **订单** 的退款原因。",
        summary: [{ label: "订单", value: "ORDER-001" }, { label: "金额", value: "¥100" }],
        fields: [{
          name: "reason", label: "退款原因", type: "SINGLE_SELECT", required: true, maxLength: 32,
          options: ["商品不符", "物流停滞", "价格变化", "不应展示"], allowCustom: true
        }, { name: "note", label: "补充说明", type: "TEXT", required: true, maxLength: 80 }]
      }
    });
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse([questionEvent]));
      if (url.includes("/workflow-runs/run-2/questions/question-2/answers")) {
        return Promise.resolve(json({ turnId: "answer-turn-2" }));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByRole("heading", { name: "确认退款原因" })).not.toBeNull();
    expect(screen.queryByRole("textbox", { name: "输入请求" })).toBeNull();
    expect(screen.getByText("ORDER-001")).not.toBeNull();
    expect(screen.getByText("¥100")).not.toBeNull();
    expect(screen.queryByText("**订单**")).toBeNull();
    expect(screen.queryByRole("option", { name: "不应展示" })).toBeNull();

    fireEvent.change(screen.getByLabelText("退款原因"), { target: { value: "__OTHER__" } });
    fireEvent.change(screen.getByLabelText("退款原因自定义内容"), { target: { value: "包装破损" } });
    fireEvent.change(screen.getByLabelText("补充说明"), { target: { value: "希望原路退回" } });
    fireEvent.click(screen.getByRole("button", { name: "提交回答" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) =>
      String(input).includes("/workflow-runs/run-2/questions/question-2/answers"))).toBe(true));
    const answerCall = fetchMock.mock.calls.find(([input]) =>
      String(input).includes("/workflow-runs/run-2/questions/question-2/answers"));
    expect(JSON.parse(String(answerCall?.[1]?.body)).answers).toEqual({ reason: "包装破损", note: "希望原路退回" });
  });

  it("QuestionCard 的 Escape 使用结构化拒绝回答取消当前业务操作", async () => {
    const thread = threadRecord("thread-1", "取消确认 Thread");
    const questionEvent = itemEvent("item-question-3", "thread-1", "turn-1", "WORKFLOW_QUESTION", 1, {
      schemaVersion: 1,
      kind: "WORKFLOW_QUESTION",
      data: {
        runId: "run-3", questionId: "question-3", checkpointId: "checkpoint-3", version: 1,
        title: "退款确认", prompt: "是否继续退款？",
        fields: [{ name: "decision", label: "决定", type: "CONFIRM", required: true, options: ["APPROVE", "REJECT"] }]
      }
    });
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse([questionEvent]));
      if (url.includes("/workflow-runs/run-3/questions/question-3/answers")) {
        return Promise.resolve(json({ turnId: "answer-turn-3" }));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    const form = (await screen.findByRole("heading", { name: "退款确认" })).closest("form");
    expect(form).not.toBeNull();
    fireEvent.keyDown(form as HTMLFormElement, { key: "Escape" });

    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) =>
      String(input).includes("/workflow-runs/run-3/questions/question-3/answers"))).toBe(true));
    const answerCall = fetchMock.mock.calls.find(([input]) =>
      String(input).includes("/workflow-runs/run-3/questions/question-3/answers"));
    expect(JSON.parse(String(answerCall?.[1]?.body)).answers).toEqual({ decision: "REJECT" });
  });

  it("SSE delta 只更新回复气泡，界面展示持久 Item 聚合的业务进度", async () => {
    const thread = threadRecord("thread-1", "业务进度 Thread");
    const deltaEvent = `event: assistant.delta\ndata: ${JSON.stringify({
      eventId: "delta-1", threadId: "thread-1", turnId: "turn-1", itemId: null,
      type: "assistant.delta", payload: "**内部原始增量**", sequence: -1, timestamp: thread.createdAt
    })}\n\n`;
    const events = [
      itemEvent("item-user-progress", "thread-1", "turn-1", "USER_MESSAGE", 1,
        { schemaVersion: 1, kind: "USER_MESSAGE", data: "查订单" }),
      itemEvent("item-context-progress", "thread-1", "turn-1", "EXECUTION_EVENT", 2,
        { schemaVersion: 1, kind: "EXECUTION_EVENT", data: "解析请求" }),
      deltaEvent
    ];
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse(events));
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("已整理请求上下文")).not.toBeNull();
    expect(screen.queryByText("assistant.delta")).toBeNull();
    expect(screen.queryByText("**内部原始增量**")).toBeNull();
    expect(screen.queryByText("运行轨迹")).toBeNull();
  });

  it("为耗尽的外部动作显示人工重试并调用稳定 API", async () => {
    const thread = threadRecord("thread-1", "人工重试 Thread");
    const retryEvents = [
      itemEvent("item-user-1", "thread-1", "turn-1", "USER_MESSAGE", 1,
        { schemaVersion: 1, kind: "USER_MESSAGE", data: "发起退款" }),
      itemEvent("item-state-1", "thread-1", "turn-1", "TURN_STATE", 2,
        { schemaVersion: 1, kind: "TURN_STATE", data: { status: "WAITING_EXTERNAL_ACTION" } }),
      itemEvent("item-action-1", "thread-1", "turn-1", "EXTERNAL_ACTION_STATUS", 3,
        { schemaVersion: 1, kind: "EXTERNAL_ACTION_STATUS", data: { runId: "run-retry", status: "MANUAL_RETRY_REQUIRED" } }),
      itemEvent("item-state-2", "thread-1", "turn-1", "TURN_STATE", 4,
        { schemaVersion: 1, kind: "TURN_STATE", data: { status: "FAILED", errorCode: "EXTERNAL_ACTION_FAILED" } })
    ];
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse(retryEvents));
      if (url.includes("/workflow-runs/run-retry/retry")) {
        return Promise.resolve(json({ runId: "run-retry", commandId: "command-retry", status: "PENDING", idempotencyKey: "idem-retry" }));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "人工重试" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) =>
      String(input).includes("/workflow-runs/run-retry/retry"))).toBe(true));
    expect((await screen.findByRole("button", { name: "重试已排队" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("外部动作成功后不覆盖已经失败的不可变 Turn", async () => {
    const thread = threadRecord("thread-1", "恢复结果 Thread");
    const events = [
      itemEvent("item-user-1", "thread-1", "turn-1", "USER_MESSAGE", 1,
        { schemaVersion: 1, kind: "USER_MESSAGE", data: "发起退款" }),
      itemEvent("item-state-1", "thread-1", "turn-1", "TURN_STATE", 2,
        { schemaVersion: 1, kind: "TURN_STATE", data: { status: "FAILED", errorCode: "EXTERNAL_ACTION_FAILED" } }),
      itemEvent("item-action-1", "thread-1", "turn-1", "EXTERNAL_ACTION_STATUS", 3,
        { schemaVersion: 1, kind: "EXTERNAL_ACTION_STATUS", data: { runId: "run-retry", status: "SUCCEEDED" } })
    ];
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) return Promise.resolve(streamResponse(events));
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);

    expect(await screen.findByText("失败")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "人工重试" })).toBeNull();
  });

  it("网络恢复后从当前游标重新连接 SSE", async () => {
    const thread = threadRecord("thread-1", "SSE 重连 Thread");
    const recoveredEvent = itemEvent("item-reconnected-1", "thread-1", "turn-1", "USER_MESSAGE", 1,
      { schemaVersion: 1, kind: "USER_MESSAGE", data: "断线后恢复" });
    let eventConnections = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/agent/threads?page=0&size=100") {
        return Promise.resolve(json({ items: [thread], page: 0, size: 100, total: 1 }));
      }
      if (url.includes("/threads/thread-1/items")) {
        return Promise.resolve(json({ items: [], afterSequence: 0, nextAfterSequence: 0, hasMore: false }));
      }
      if (url.includes("/threads/thread-1/events")) {
        eventConnections += 1;
        return eventConnections === 1
          ? Promise.resolve(pendingStreamResponse())
          : Promise.resolve(streamResponse([recoveredEvent]));
      }
      throw new Error(`unexpected request: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<App />);
    await waitFor(() => expect(eventConnections).toBe(1));
    window.dispatchEvent(new Event("offline"));
    window.dispatchEvent(new Event("online"));

    await waitFor(() => expect(eventConnections).toBe(2));
    expect((await screen.findAllByText("断线后恢复")).length).toBeGreaterThan(0);
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes("/events?afterSequence=0")).length)
      .toBe(2);
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
    expect(await screen.findByRole("heading", { name: "从一个售后问题开始" })).not.toBeNull();

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

function pendingStreamResponse() {
  return new Response(new ReadableStream<Uint8Array>());
}

function itemEvent(
  eventId: string,
  threadId: string,
  turnId: string,
  type: string,
  sequence: number,
  payload: unknown
) {
  return `event: item.${type.toLowerCase()}\ndata: ${JSON.stringify({
    eventId,
    threadId,
    turnId,
    itemId: eventId,
    type: `item.${type.toLowerCase()}`,
    payload: JSON.stringify(payload),
    sequence,
    timestamp: new Date().toISOString()
  })}\n\n`;
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
