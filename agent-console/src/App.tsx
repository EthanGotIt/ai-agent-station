import { useEffect, useState } from "react";
import { Bot, ClipboardList, Database, Settings2, Signal } from "lucide-react";
import { AfterSalesReviewPanel } from "./AfterSalesReviewPanel";
import { AgentWorkspace } from "./AgentWorkspace";
import { MemoryPanel } from "./MemoryPanel";
import { useAgentStream } from "./useAgentStream";
import type { WorkspaceId } from "./types";

const WORKSPACES: Array<{ id: WorkspaceId; label: string; description: string; icon: typeof Bot }> = [
  { id: "agent", label: "Agent", description: "业务闭环工作台", icon: Bot },
  { id: "after-sales", label: "售后审核", description: "退款与人工处理", icon: ClipboardList },
  { id: "memory", label: "会话记忆", description: "偏好与证据", icon: Database }
];

function workspaceFromHash(): WorkspaceId {
  const value = window.location.hash.replace(/^#\/?/, "");
  return WORKSPACES.some((item) => item.id === value) ? value as WorkspaceId : "agent";
}

function useWorkspace() {
  const [workspace, setWorkspace] = useState<WorkspaceId>(workspaceFromHash);
  useEffect(() => {
    const update = () => setWorkspace(workspaceFromHash());
    window.addEventListener("hashchange", update);
    if (!window.location.hash) window.history.replaceState(null, "", "#/agent");
    return () => window.removeEventListener("hashchange", update);
  }, []);
  const navigate = (next: WorkspaceId) => {
    window.location.hash = `/${next}`;
  };
  return { navigate, workspace };
}

function WorkspaceNavigation({ workspace, onNavigate, mobile = false }: {
  workspace: WorkspaceId;
  onNavigate: (workspace: WorkspaceId) => void;
  mobile?: boolean;
}) {
  return <nav className={mobile ? "mobile-navigation" : "workspace-navigation"} aria-label="工作区">
    {WORKSPACES.map((item) => {
      const Icon = item.icon;
      const selected = workspace === item.id;
      return <button className={selected ? "active" : ""} type="button" key={item.id}
        aria-current={selected ? "page" : undefined} onClick={() => onNavigate(item.id)}>
        <Icon aria-hidden="true" />
        <span><strong>{item.label}</strong>{mobile ? null : <small>{item.description}</small>}</span>
      </button>;
    })}
  </nav>;
}

function workspaceTitle(workspace: WorkspaceId) {
  return WORKSPACES.find((item) => item.id === workspace)?.description ?? "业务闭环工作台";
}

/** 控制台应用外壳：通过 Hash 工作区把业务操作和工程检查按需分层。 */
export function App() {
  const [userId, setUserId] = useState("demo-user-1");
  const [sessionId, setSessionId] = useState("demo-session-1");
  const [operatorId, setOperatorId] = useState("demo-operator-1");
  const [memoryUse, setMemoryUse] = useState(true);
  const [memoryGenerate, setMemoryGenerate] = useState(false);
  const { navigate, workspace } = useWorkspace();
  const agent = useAgentStream(userId.trim());

  return <div className="app-shell">
    <aside className="app-rail">
      <div className="brand-lockup"><span className="brand-mark"><Bot aria-hidden="true" /></span><div><strong>Agent Workbench</strong><small>业务闭环控制台</small></div></div>
      <WorkspaceNavigation workspace={workspace} onNavigate={navigate} />
      <div className="rail-footnote"><Signal aria-hidden="true" /><span>本机运行 · 演示模式</span></div>
    </aside>
    <div className="app-page">
      <header className="app-topbar">
        <div><span className="topbar-section">当前工作区</span><strong>{workspaceTitle(workspace)}</strong></div>
        <details className="session-context">
          <summary><Settings2 aria-hidden="true" /><span>会话上下文</span><small>{userId.trim() || "未设置用户"} · {sessionId.trim() || "未设置会话"}</small></summary>
          <div className="context-fields">
            <label>用户 ID<input value={userId} disabled={agent.busy} onChange={(event) => setUserId(event.target.value)} /></label>
            <label>会话 ID<input value={sessionId} disabled={agent.busy} onChange={(event) => setSessionId(event.target.value)} /></label>
            <label>操作员 ID<input value={operatorId} disabled={agent.busy} onChange={(event) => setOperatorId(event.target.value)} /></label>
            <label className="context-toggle"><input type="checkbox" checked={memoryUse} disabled={agent.busy} onChange={(event) => setMemoryUse(event.target.checked)} /><span>使用会话记忆</span></label>
            <label className="context-toggle"><input type="checkbox" checked={memoryGenerate} disabled={agent.busy} onChange={(event) => setMemoryGenerate(event.target.checked)} /><span>后台生成记忆</span></label>
          </div>
        </details>
      </header>
      <main className="workbench-content">
        {workspace === "agent" ? <AgentWorkspace agent={agent} sessionId={sessionId} memoryUse={memoryUse} memoryGenerate={memoryGenerate} /> : null}
        {workspace === "after-sales" ? <AfterSalesReviewPanel operatorId={operatorId.trim()} /> : null}
        {workspace === "memory" ? <MemoryPanel userId={userId.trim()} sessionId={sessionId.trim()} disabled={agent.busy} /> : null}
      </main>
    </div>
    <WorkspaceNavigation mobile workspace={workspace} onNavigate={navigate} />
  </div>;
}
