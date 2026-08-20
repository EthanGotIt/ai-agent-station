import { appendSseChunk } from "./sse";
import { describe, expect, it } from "vitest";

describe("appendSseChunk", () => {
  it("keeps partial frames and joins multiline data", () => {
    const events: Array<[string, unknown]> = [];
    const remainder = appendSseChunk(
      "event: item.assistant_message\r\ndata: {\"schemaVersion\":\r\ndata: 1,\"kind\":\"ASSISTANT_MESSAGE\",\"data\":\"order_overview\"}\r\n\r\nevent: turn.completed\ndata: {\"status\":\"COM",
      (type, data) => events.push([type, data])
    );

    expect(events).toEqual([["item.assistant_message", { schemaVersion: 1, kind: "ASSISTANT_MESSAGE", data: "order_overview" }]]);
    expect(remainder).toBe("event: turn.completed\ndata: {\"status\":\"COM");
  });

  it("flushes a final unterminated frame when the stream closes", () => {
    const events: Array<[string, unknown]> = [];
    const remainder = appendSseChunk(
      "event: turn.completed\r\ndata: COMPLETED",
      (type, data) => events.push([type, data])
    );

    appendSseChunk(`${remainder}\n\n`, (type, data) => events.push([type, data]));

    expect(events).toEqual([["turn.completed", "COMPLETED"]]);
  });

  it("parses ready and heartbeat control events", () => {
    const events: Array<[string, unknown]> = [];

    appendSseChunk(
      "event: ready\ndata: {\"afterSequence\":0}\n\nevent: heartbeat\ndata: {\"afterSequence\":1}\n\n",
      (type, data) => events.push([type, data])
    );

    expect(events).toEqual([
      ["ready", { afterSequence: 0 }],
      ["heartbeat", { afterSequence: 1 }]
    ]);
  });

  it("keeps malformed payloads attached to their wire event", () => {
    const events: Array<[string, unknown]> = [];

    appendSseChunk(
      "event: item.workflow_question\ndata: not-json\n\n",
      (type, data) => events.push([type, data])
    );

    expect(events).toEqual([["item.workflow_question", "not-json"]]);
  });
});
