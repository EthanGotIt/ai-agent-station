import type { Intervention } from "./types";

type Props = {
  value: Intervention;
  disabled: boolean;
  onDecide: (decision: "CONFIRM" | "REJECT") => void;
};

export function InterventionCard({ value, disabled, onDecide }: Props) {
  return <section className="card intervention-card">
    <p className="eyebrow">ReAct / confirmation required</p>
    <h2>确认工具写入</h2>
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
