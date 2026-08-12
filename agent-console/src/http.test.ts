import { describe, expect, it } from "vitest";
import { readJsonResponse } from "./http";

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
});
