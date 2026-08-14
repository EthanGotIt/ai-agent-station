import { useState, type FormEvent, type KeyboardEvent } from "react";
import {
  BookMarked,
  Bot,
  CircleAlert,
  ClipboardCheck,
  PackageSearch,
  Plus,
  Send,
  Square,
  Truck
} from "lucide-react";
import { ExecutionInspector } from "./ExecutionInspector";
import { InterventionCard } from "./InterventionCard";
import { QuestionCard } from "./QuestionCard";
import { SCENARIOS } from "./scenarios";
import { StructuredCard } from "./StructuredCard";
import { useAgentStream } from "./useAgentStream";
import type { ConversationTurn, ScenarioDefinition } from "./types";

type Props = {
  agent: ReturnType<typeof useAgentStream>;
  sessionId: string;
  memoryUse: boolean;
  memoryGenerate: boolean;
};

const scenarioIcons = {
  order: PackageSearch,
  logistics: Truck,
  refund: ClipboardCheck,
  preference: BookMarked
};

function statusLabel(status: ConversationTurn["status"]) {
  const labels: Record<ConversationTurn["status"], string> = {
    running: "处理中",
    waiting: "等待你的决定",
    completed: "已完成",
    cancelled: "已取消",
    failed: "需要重试"
  };
  return labels[status];
}

function time(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function ScenarioLauncher({ disabled, onRun }: { disabled: boolean; onRun: (scenario: ScenarioDefinition) => void }) {
  return <section className="scenario-launcher" aria-labelledby="scenario-heading">
    <div className="workspace-section-heading">
      <div><h2 id="scenario-heading">从一个真实场景开始</h2><p>每条快捷指令都对应仓库初始化的演示数据和现有业务能力。</p></div>
    </div>
    <div className="scenario-list">
      {SCENARIOS.map((scenario) => {
        const Icon = scenarioIcons[scenario.id];
        return <button className="scenario-action" type="button" key={scenario.id} disabled={disabled} onClick={() => onRun(scenario)}>
          <Icon aria-hidden="true" />
          <span><strong>{scenario.title}</strong><small>{scenario.description}</small></span>
        </button>;
      })}
    </div>
  </section>;
}

function ConversationTurnCard({ turn }: { turn: ConversationTurn }) {
  return <article className={`conversation-turn turn-${turn.status}`}>
    <div className="turn-request">
      <span className="turn-avatar user-avatar">你</span>
      <div><div className="turn-meta"><strong>本次请求</strong><time dateTime={turn.startedAt}>{time(turn.startedAt)}</time></div><p>{turn.userMessage}</p></div>
    </div>
    <div className="turn-response">
      <span className="turn-avatar agent-avatar"><Bot aria-label="Agent" /></span>
      <div className="turn-response-body">
        <div className="turn-meta"><strong>Agent</strong><span className={`turn-status status-${turn.status}`}>{statusLabel(turn.status)}</span></div>
        {turn.route ? <p className="turn-route">已选择 {turn.route} 路径</p> : null}
        {turn.content ? <p className="agent-content">{turn.content}</p> : turn.status === "running" ? <p className="agent-content loading-copy">正在理解请求并建立执行路径…</p> : null}
        {turn.result ? <StructuredCard data={turn.result} /> : null}
        {turn.error ? <p className="turn-error" role="alert"><CircleAlert aria-hidden="true" />{turn.error}</p> : null}
      </div>
    </div>
  </article>;
}

/** Agent 主工作区：将可读对话、业务动作与工程轨迹保持在同一回合上下文。 */
export function AgentWorkspace({ agent, sessionId, memoryUse, memoryGenerate }: Props) {
  const [message, setMessage] = useState("");
  const normalizedSessionId = sessionId.trim();

  function runScenario(scenario: ScenarioDefinition) {
    if (!normalizedSessionId || agent.busy) return;
    void agent.sendChat(normalizedSessionId, scenario.message, { use: memoryUse, generate: memoryGenerate });
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = message.trim();
    if (!next || !normalizedSessionId || agent.busy) return;
    void agent.sendChat(normalizedSessionId, next, { use: memoryUse, generate: memoryGenerate });
    setMessage("");
  }

  function submitFromKeyboard(event: KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  }

  return <section className="agent-workspace" aria-labelledby="agent-workspace-heading">
    <div className="workspace-heading">
      <div>
        <h1 id="agent-workspace-heading">让一次请求走完整个业务闭环</h1>
        <p>从路由到结果，业务动作和执行轨迹始终保持在同一条可读路径上。</p>
      </div>
      <button className="secondary icon-button" type="button" disabled={agent.busy || agent.turns.length === 0} onClick={agent.clearView}>
        <Plus aria-hidden="true" />新建本地视图
      </button>
    </div>
    <div className="agent-layout">
      <div className="conversation-canvas">
        <ScenarioLauncher disabled={agent.busy || !normalizedSessionId} onRun={runScenario} />
        <section className="conversation-stream" aria-label="Agent 对话回合">
          {agent.turns.length === 0 ? <div className="conversation-empty">
            <Bot aria-hidden="true" />
            <h2>业务闭环从这里开始</h2>
            <p>选择一个真实场景，或在下方描述订单、物流、退款或会话偏好请求。</p>
          </div> : agent.turns.map((turn) => <ConversationTurnCard key={turn.id} turn={turn} />)}
        </section>
        {agent.question ? <QuestionCard key={agent.question.question.questionId} value={agent.question} disabled={agent.busy} onSubmit={(answers) => {
          const { question, workflowRun } = agent.question!;
          void agent.answer({
            sessionId: normalizedSessionId,
            runId: workflowRun.runId,
            questionId: question.questionId,
            checkpointId: workflowRun.checkpointId,
            expectedVersion: workflowRun.version,
            answers,
            memory: { use: memoryUse, generate: memoryGenerate }
          });
        }} /> : null}
        {agent.intervention ? <InterventionCard value={agent.intervention} disabled={agent.deciding} onDecide={(decision) => {
          void agent.decide(normalizedSessionId, decision);
        }} /> : null}
        <form className={`composer ${agent.turns.length === 0 ? "composer-idle" : ""}`} onSubmit={submit}>
          <label htmlFor="agent-message">输入请求</label>
          <textarea id="agent-message" value={message} disabled={agent.busy} onKeyDown={submitFromKeyboard}
            onChange={(event) => setMessage(event.target.value)} placeholder="例如：订单 ORDER-SHIPPED-STALLED-001 物流停滞怎么办？" />
          <div className="composer-actions">
            <span>Ctrl / ⌘ + Enter 发送</span>
            {agent.busy ? <button className="secondary icon-button" type="button" onClick={() => void agent.cancel()}><Square aria-hidden="true" />取消当前请求</button> : <button className="icon-button" disabled={!message.trim() || !normalizedSessionId} type="submit"><Send aria-hidden="true" />发送请求</button>}
          </div>
        </form>
      </div>
      <ExecutionInspector events={agent.traceEvents} busy={agent.busy} />
    </div>
  </section>;
}
