import { ShieldAlert } from "lucide-react";
import type { Intervention } from "./types";

type Props = {
  value: Intervention;
  disabled: boolean;
  onDecide: (decision: "CONFIRM" | "REJECT") => void;
};

export function InterventionCard({ value, disabled, onDecide }: Props) {
  return <section className="decision-card intervention-card" aria-labelledby={`intervention-${value.replyId}`}>
    <div className="decision-heading"><ShieldAlert aria-hidden="true" /><div><h2 id={`intervention-${value.replyId}`}>确认工具写入</h2><p>ReAct 需要你明确决定后才能继续当前回合。</p></div></div>
    <p>{value.message}</p>
    {value.tools.map((tool) => <article className="tool" key={tool.toolCallId}>
      <strong>{tool.toolName}</strong>
      <code>{JSON.stringify(tool.arguments)}</code>
    </article>)}
    <div className="actions">
      <button disabled={disabled} onClick={() => onDecide("CONFIRM")}>确认执行</button>
      <button className="secondary" disabled={disabled} onClick={() => onDecide("REJECT")}>拒绝</button>
    </div>
  </section>;
}
