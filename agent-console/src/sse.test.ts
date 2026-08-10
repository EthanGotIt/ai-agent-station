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
      "event: done\r\ndata: {\"status\":\"COMPLETED\"}",
      (type, data) => events.push([type, data])
    );

    appendSseChunk(`${remainder}\n\n`, (type, data) => events.push([type, data]));

    expect(events).toEqual([["done", { status: "COMPLETED" }]]);
  });
});
