import { useState, type FormEvent } from "react";
import { AfterSalesReviewPanel } from "./AfterSalesReviewPanel";
import { InterventionCard } from "./InterventionCard";
import { MemoryPanel } from "./MemoryPanel";
import { QuestionCard } from "./QuestionCard";
import { StructuredCard } from "./StructuredCard";
import { useAgentStream } from "./useAgentStream";

export function App() {
  const [userId, setUserId] = useState("demo-user-1");
  const [sessionId, setSessionId] = useState("demo-session-1");
  const [operatorId, setOperatorId] = useState("demo-operator-1");
  const [message, setMessage] = useState("");
  const [memoryUse, setMemoryUse] = useState(true);
  const [memoryGenerate, setMemoryGenerate] = useState(false);
  const agent = useAgentStream(userId.trim());

  function submitChat(event: FormEvent) {
    event.preventDefault();
    if (message.trim() && !agent.busy) {
      void agent.sendChat(sessionId.trim(), message.trim(), { use: memoryUse, generate: memoryGenerate });
      setMessage("");
    }
  }

  return <main>
    <header>
      <div>
        <h1>业务闭环控制台</h1>
      </div>
      <button className="secondary" disabled={!agent.busy} onClick={() => void agent.cancel()}>取消当前请求</button>
    </header>
    <section className="settings card">
      <label>用户 ID<input value={userId} disabled={agent.busy} onChange={(event) => setUserId(event.target.value)} /></label>
      <label>会话 ID<input value={sessionId} disabled={agent.busy} onChange={(event) => setSessionId(event.target.value)} /></label>
      <label>操作员 ID<input value={operatorId} onChange={(event) => setOperatorId(event.target.value)} /></label>
      <label><input type="checkbox" checked={memoryUse} onChange={(event) => setMemoryUse(event.target.checked)} /> 使用会话记忆</label>
      <label><input type="checkbox" checked={memoryGenerate} onChange={(event) => setMemoryGenerate(event.target.checked)} /> 后台生成记忆</label>
    </section>
    <section className="workspace">
      <section className="timeline card">
        <h2>Chat / SSE 时间线</h2>
        {agent.timeline.length === 0 ? <p className="muted">发送订单、物流、退款或偏好请求以开始。</p> : agent.timeline.map((event) => <article className="event" key={event.id}>
          <p><strong>{event.type}</strong> <small>{new Date(event.at).toLocaleTimeString()}</small></p>
          {event.type === "result" ? <StructuredCard data={event.data} /> : <pre>{typeof event.data === "string" ? event.data : JSON.stringify(event.data, null, 2)}</pre>}
        </article>)}
      </section>
      <aside>
        <AfterSalesReviewPanel operatorId={operatorId.trim()} />
        {agent.question ? <QuestionCard value={agent.question} disabled={agent.busy} onSubmit={(answers) => {
          const { question, workflowRun } = agent.question!;
          void agent.answer({
            sessionId: sessionId.trim(), runId: workflowRun.runId, questionId: question.questionId,
            checkpointId: workflowRun.checkpointId, expectedVersion: workflowRun.version, answers,
            memory: { use: memoryUse, generate: memoryGenerate }
          });
        }} /> : null}
        {agent.intervention ? <InterventionCard value={agent.intervention} disabled={agent.deciding} onDecide={(decision) => {
          void agent.decide(sessionId.trim(), decision);
        }} /> : null}
        <MemoryPanel userId={userId.trim()} sessionId={sessionId.trim()} disabled={agent.busy} />
      </aside>
    </section>
    <form className="composer card" onSubmit={submitChat}>
      <label>消息<textarea value={message} disabled={agent.busy} onChange={(event) => setMessage(event.target.value)} placeholder="例如：订单 ORDER-SHIPPED-STALLED-001 物流为什么没更新？" /></label>
      <button disabled={agent.busy || !message.trim()} type="submit">发送</button>
    </form>
  </main>;
}
