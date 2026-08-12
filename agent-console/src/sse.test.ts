import { appendSseChunk } from "./sse";
import { describe, expect, it } from "vitest";

describe("appendSseChunk", () => {
  it("keeps partial frames and joins multiline data", () => {
    const events: Array<[string, unknown]> = [];
    const remainder = appendSseChunk(
      "event: result\r\ndata: {\"cardType\":\r\ndata: \"order_overview\"}\r\n\r\nevent: done\ndata: {\"status\":\"COM",
      (type, data) => events.push([type, data])
    );

    expect(events).toEqual([["result", { cardType: "order_overview" }]]);
    expect(remainder).toBe("event: done\ndata: {\"status\":\"COM");
  });

  it("flushes a final unterminated frame when the stream closes", () => {
    const events: Array<[string, unknown]> = [];
    const remainder = appendSseChunk(
      "event: done\r\ndata: COMPLETED",
      (type, data) => events.push([type, data])
    );

    appendSseChunk(`${remainder}\n\n`, (type, data) => events.push([type, data]));

    expect(events).toEqual([["done", "COMPLETED"]]);
  });

  it("preserves textual lifecycle events without treating them as JSON", () => {
    const events: Array<[string, unknown]> = [];

    appendSseChunk(
      "event: route\ndata: WORKFLOW\n\nevent: progress\ndata: request_started\n\nevent: tool\ndata: query_order:SUCCESS\n\n",
      (type, data) => events.push([type, data])
    );

    expect(events).toEqual([
      ["route", "WORKFLOW"],
      ["progress", "request_started"],
      ["tool", "query_order:SUCCESS"]
    ]);
  });

  it("reports malformed structured events", () => {
    const events: Array<[string, unknown]> = [];

    appendSseChunk(
      "event: intervention\ndata: not-json\n\n",
      (type, data) => events.push([type, data])
    );

    expect(events).toEqual([["error", "SSE payload could not be parsed"]]);
  });
});
