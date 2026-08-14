import { CheckCircle2, CircleDotDashed, CircleX, Route, Wrench } from "lucide-react";
import type { RunTraceEvent } from "./types";

type Props = { events: RunTraceEvent[]; busy: boolean };

function eventLabel(event: RunTraceEvent) {
  const labels: Record<RunTraceEvent["type"], string> = {
    route: "路由",
    node: "工作流节点",
    progress: "运行进度",
    tool: "工具调用",
    done: "完成",
    error: "异常"
  };
  return labels[event.type];
}

function eventIcon(event: RunTraceEvent) {
  if (event.type === "route") return <Route aria-hidden="true" />;
  if (event.type === "tool") return <Wrench aria-hidden="true" />;
  if (event.type === "done") return <CheckCircle2 aria-hidden="true" />;
  if (event.type === "error") return <CircleX aria-hidden="true" />;
  return <CircleDotDashed aria-hidden="true" />;
}

function eventValue(event: RunTraceEvent) {
  const labels: Record<string, string> = {
    queued: "已进入会话队列",
    request_started: "请求已开始",
    thinking_started: "正在准备分析",
    thinking_completed: "分析准备完成",
    model_call_started: "正在调用模型",
    model_call_completed: "模型调用完成",
    WORKFLOW: "确定性 Workflow",
    REACT: "ReAct 分析",
    ATOMIC: "原子处理",
    CLARIFY: "需要补充信息",
    COMPLETED: "已完成",
    CANCELLED: "已取消",
    FAILED: "未完成"
  };
  return labels[event.data] ?? event.data;
}

function clock(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}

/** 执行检查器：将 SSE 生命周期转为按需阅读的可视化轨迹。 */
export function ExecutionInspector({ events, busy }: Props) {
  const latest = events.slice(-14);
  return <aside className="execution-inspector" aria-label="执行检查器" aria-live="polite">
    <div className="inspector-heading">
      <div>
        <h2>执行检查器</h2>
        <p>{busy ? "当前回合正在运行" : events.length > 0 ? "本地视图中的最近回合" : "等待第一个 Agent 回合"}</p>
      </div>
      <span className={`run-indicator ${busy ? "running" : ""}`}>{busy ? "运行中" : "就绪"}</span>
    </div>
    {latest.length === 0 ? <div className="trace-empty">
      <CircleDotDashed aria-hidden="true" />
      <p>路由、Workflow、工具与完成状态会在这里按顺序出现。</p>
    </div> : <ol className="trace-list">
      {latest.map((event) => <li className={`trace-event trace-${event.type}`} key={event.id}>
        <span className="trace-icon">{eventIcon(event)}</span>
        <div>
          <div className="trace-meta"><strong>{eventLabel(event)}</strong><time dateTime={event.at}>{clock(event.at)}</time></div>
          <p>{eventValue(event)}</p>
        </div>
      </li>)}
    </ol>}
  </aside>;
}
