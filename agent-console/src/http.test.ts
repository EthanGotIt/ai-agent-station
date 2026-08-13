import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpRequestError, readJsonResponse, requestJson } from "./http";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("readJsonResponse", () => {
  it("parses successful JSON responses", async () => {
    const result = await readJsonResponse<{ status: string }>(
      new Response('{"status":"ok"}', { status: 200 })
    );

    expect(result).toEqual({ status: "ok" });
  });

  it("accepts a successful empty response", async () => {
    const result = await readJsonResponse<void>(new Response(null, { status: 204 }));

    expect(result).toBeUndefined();
  });

  it("uses the structured error message", async () => {
    const response = new Response('{"code":"MEMORY_CONFLICT","message":"版本冲突"}', { status: 409 });

    await expect(readJsonResponse(response)).rejects.toThrow("版本冲突");
  });

  it("preserves status and business code for conflict recovery", async () => {
    const response = new Response('{"code":"AFTER_SALES_CASE_CONFLICT","message":"状态已变化"}', { status: 409 });

    await expect(readJsonResponse(response)).rejects.toMatchObject({
      name: "HttpRequestError", kind: "http", status: 409, code: "AFTER_SALES_CASE_CONFLICT"
    } satisfies Partial<HttpRequestError>);
  });

  it("distinguishes caller cancellation from request timeout", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn((_input: RequestInfo | URL, options?: RequestInit) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    })));

    const caller = new AbortController();
    const cancelled = requestJson("/cancelled", { signal: caller.signal });
    caller.abort();
    await expect(cancelled).rejects.toMatchObject({ kind: "aborted" } satisfies Partial<HttpRequestError>);

    const timedOut = requestJson("/timed-out", { timeoutMs: 1 });
    const timeoutExpectation = expect(timedOut).rejects.toMatchObject({ kind: "timeout" } satisfies Partial<HttpRequestError>);
    await vi.advanceTimersByTimeAsync(1);
    await timeoutExpectation;
  });
});
