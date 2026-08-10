/** 将 Fetch ReadableStream 中的不完整 SSE 帧归并为稳定事件。 */
export function appendSseChunk(
  buffer: string,
  append: (type: string, data: unknown) => void
): string {
  const blocks = buffer.replace(/\r\n/g, "\n").split("\n\n");
  const remainder = blocks.pop() ?? "";
  for (const block of blocks) {
    const lines = block.split("\n");
    let event = "message";
    const data: string[] = [];
    for (const line of lines) {
      if (line.startsWith("event:")) event = line.slice(6).trim() || "message";
      if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
    }
    if (data.length === 0) continue;
    const payload = data.join("\n");
    try {
      append(event, JSON.parse(payload));
    } catch {
      append("error", "SSE payload could not be parsed");
    }
  }
  return remainder;
}
