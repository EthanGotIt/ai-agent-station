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
  throw new Error(error.message ?? error.code ?? `Request failed: ${response.status}`);
}
