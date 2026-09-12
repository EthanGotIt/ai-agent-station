const ERROR_COPY: Record<string, string> = {
  AGENT_DECISION_MISSING: "系统未能完成本轮判断，可以再次尝试。",
  CONTEXT_BUDGET_EXCEEDED: "本轮处理已达到资源上限，订单结果未改变；可以稍后重试。",
  OUTPUT_BUDGET_EXCEEDED: "本轮处理已达到资源上限，订单结果未改变；可以稍后重试。",
  CONTEXT_HISTORY_INVALID: "订单历史读取失败，业务结果保持不变；可以重试。",
  TOOL_REPEATED_FAILURE: "订单信息暂时无法确认，可以稍后重试。",
  EXTERNAL_ACTION_FAILED: "订单操作未完成，请查看订单卡片中的状态。",
  FIXTURE_TRANSIENT_FAILURE: "外部订单系统暂未完成处理，请查看订单卡片中的状态。"
};

const CONCLUSION_COPY: Record<string, string> = {
  ...ERROR_COPY,
  MANUAL_RETRY_REQUIRED: "自动重试已用尽，需要人工重试。"
};

const HTTP_STATUS_COPY: Record<number, string> = {
  400: "请求参数有误，请检查后重试。",
  401: "登录状态已失效，请重新登录。",
  403: "当前账户没有执行此操作的权限。",
  404: "找不到对应的对话或订单记录。",
  429: "请求太频繁，请稍后再试。",
  500: "订单服务暂时不可用，请稍后重试。",
  502: "订单服务暂时不可用，请稍后重试。",
  503: "订单服务暂时不可用，请稍后重试。",
  504: "订单服务暂时不可用，请稍后重试。"
};

type FailureShape = { kind?: unknown; status?: unknown; code?: unknown; message?: unknown };

/** 将稳定的内部错误码转换为用户能理解的结果和下一步。 */
export function clarifyError(value: string, errorCode?: string | null) {
  return ERROR_COPY[errorCode ?? value] ?? ERROR_COPY[value] ?? (/^[A-Z][A-Z0-9_]+$/.test(value.trim())
    ? "系统暂时未能完成这次处理，请查看订单卡片中的状态。"
    : value);
}

/** 统一请求失败的可见文案，避免把服务端堆栈、内部码或原始错误体带到主界面。 */
export function clarifyRequestFailure(failure: unknown, fallback: string) {
  const value = failure && typeof failure === "object" ? failure as FailureShape : {};
  const kind = typeof value.kind === "string" ? value.kind : null;
  const status = typeof value.status === "number" ? value.status : null;
  const code = typeof value.code === "string" ? value.code : null;
  if (kind === "aborted") return "请求已取消。";
  if (kind === "timeout") return "请求超时，请稍后重试。";
  if (kind === "network") return "网络连接暂时不可用，请检查网络后重试。";
  if (status === 409 && code === "THREAD_AWAITING_ANSWER") return "当前对话正在等待你的回答，请先完成上方确认。";
  if (status === 409 && code === "WORKFLOW_VERSION_CONFLICT") return "确认面板已更新，请重新检查当前订单状态后再试。";
  if (status !== null && HTTP_STATUS_COPY[status]) return HTTP_STATUS_COPY[status];
  if (status !== null && status >= 500) return "订单服务暂时不可用，请稍后重试。";
  if (code) return clarifyError(fallback, code);
  return fallback;
}

/** 只替换已知技术码，保留助手结论中的订单事实和上下文。 */
export function clarifyConclusion(value: string) {
  return value.replace(/\b[A-Z][A-Z0-9_]+\b/g, (code) => CONCLUSION_COPY[code] ?? code);
}
