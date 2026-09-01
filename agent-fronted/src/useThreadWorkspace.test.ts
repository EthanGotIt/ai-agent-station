import { describe, expect, it } from "vitest";
import { replayPageProgress } from "./useThreadWorkspace";
import type { AgentItemPage } from "./threadTypes";

function item(sequence: number) {
  return {
    itemId: `item-${sequence}`,
    turnId: "turn-1",
    sequence,
    type: "USER_MESSAGE",
    schemaVersion: 1,
    payload: "{}",
    createdAt: "2026-08-28T00:00:00Z"
  };
}

function page(items: unknown[]): AgentItemPage {
  return { items } as unknown as AgentItemPage;
}

describe("Thread history replay cursor", () => {
  it("stops when a page repeats the current cursor", () => {
    const progress = replayPageProgress(
      { ...page([item(4)]), nextAfterSequence: 4, hasMore: true }, 4);

    expect(progress.nextAfterSequence).toBe(4);
    expect(progress.hasMore).toBe(false);
  });

  it("uses the greatest item sequence when the response cursor is stale", () => {
    const progress = replayPageProgress(
      { ...page([item(5), item(8)]), nextAfterSequence: 5, hasMore: true }, 4);

    expect(progress.nextAfterSequence).toBe(8);
    expect(progress.hasMore).toBe(true);
  });

  it("does not continue a page with no items", () => {
    const progress = replayPageProgress(
      { ...page([]), nextAfterSequence: 9, hasMore: true }, 4);

    expect(progress.nextAfterSequence).toBe(9);
    expect(progress.hasMore).toBe(false);
  });

  it("drops malformed items and closes the replay cursor", () => {
    const progress = replayPageProgress(
      { ...page([null, { itemId: "new-5", type: "USER_MESSAGE", sequence: 5, payload: "{}" }]),
        nextAfterSequence: 5, hasMore: true }, 4);

    expect(progress.items).toHaveLength(1);
    expect(progress.nextAfterSequence).toBe(5);
    expect(progress.hasMore).toBe(false);
  });
});
