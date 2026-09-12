import { describe, expect, it } from "vitest";
import { HttpRequestError } from "./http";
import { clarifyConclusion, clarifyError, clarifyRequestFailure } from "./userFacingCopy";

describe("user-facing copy", () => {
  it("将内部错误码转换为可行动的错误说明", () => {
    expect(clarifyError("FIXTURE_TRANSIENT_FAILURE")).toBe("外部订单系统暂未完成处理，请查看订单卡片中的状态。");
    expect(clarifyError("原始错误", "AGENT_DECISION_MISSING")).toBe("系统未能完成本轮判断，可以再次尝试。");
  });

  it("在助手结论中隐藏已知技术码并保留上下文", () => {
    expect(clarifyConclusion("提交结果：FIXTURE_TRANSIENT_FAILURE")).toBe("提交结果：外部订单系统暂未完成处理，请查看订单卡片中的状态。");
  });

  it("按 API 状态提供恢复动作，并隐藏服务端原文", () => {
    expect(clarifyRequestFailure(
      new HttpRequestError("stack trace", "http", 403, "PERMISSION_DENIED"),
      "订单动作提交失败"
    )).toBe("当前账户没有执行此操作的权限。");
    expect(clarifyRequestFailure(
      new HttpRequestError("raw conflict", "http", 409, "THREAD_AWAITING_ANSWER"),
      "Turn 提交失败"
    )).toBe("当前对话正在等待你的回答，请先完成上方确认。");
    expect(clarifyRequestFailure(new Error("server details"), "工作区加载失败")).toBe("工作区加载失败");
  });
});
