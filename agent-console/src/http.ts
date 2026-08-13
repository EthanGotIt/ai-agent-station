export type HttpRequestErrorKind = "aborted" | "http" | "network" | "timeout";

/** HTTP 边界错误：保留稳定状态和业务码，供界面区分冲突、超时和用户取消。 */
export class HttpRequestError extends Error {
  constructor(
    message: string,
    readonly kind: HttpRequestErrorKind,
    readonly status?: number,
    readonly code?: string
  ) {
    super(message);
    this.name = "HttpRequestError";
  }
}

type RequestJsonOptions = RequestInit & { timeoutMs?: number };

/** 读取 JSON 响应，同时接受 204 等成功空响应。 */
export async function readJsonResponse<T>(response: Response): Promise<T> {
  const body = await response.text();
  if (response.ok) {
    return body ? JSON.parse(body) as T : undefined as T;
  }
  let error: { code?: string; message?: string } = {};
  try {
    error = body ? JSON.parse(body) as typeof error : {};
  } catch {
    // 非 JSON 错误体仍使用稳定的 HTTP 状态降级信息。
  }
  throw new HttpRequestError(
    error.message ?? error.code ?? `Request failed: ${response.status}`,
    "http",
    response.status,
    error.code
  );
}

/** 非流式请求统一拥有超时、外部取消和稳定错误语义；SSE 不使用此封装。 */
export async function requestJson<T>(input: RequestInfo | URL, options: RequestJsonOptions = {}): Promise<T> {
  const { signal, timeoutMs = 10_000, ...request } = options;
  const controller = new AbortController();
  let cancelledByCaller = false;
  const abortFromCaller = () => {
    cancelledByCaller = true;
    controller.abort(signal?.reason);
  };
  if (signal?.aborted) abortFromCaller();
  else signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(input, { ...request, signal: controller.signal });
    return await readJsonResponse<T>(response);
  } catch (failure) {
    if (failure instanceof HttpRequestError) throw failure;
    if (controller.signal.aborted) {
      throw new HttpRequestError(
        cancelledByCaller ? "请求已取消" : "请求超时，请稍后重试。",
        cancelledByCaller ? "aborted" : "timeout"
      );
    }
    throw new HttpRequestError(
      "网络连接暂时不可用，请检查网络后重试。",
      "network"
    );
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener("abort", abortFromCaller);
  }
}

export function isRequestAbort(failure: unknown): boolean {
  return failure instanceof HttpRequestError && failure.kind === "aborted";
}
