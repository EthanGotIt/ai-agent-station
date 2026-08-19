import { Bot, CircleUserRound, Settings2, Signal } from "lucide-react";
import { useState } from "react";
import { ThreadWorkspace } from "./ThreadWorkspace";
import { useThreadWorkspace } from "./useThreadWorkspace";

/** Commerce Guardian Agent 工作区外壳：身份只用于演示认证上下文，业务操作全部落到 Thread。 */
export function App() {
  const [userId, setUserId] = useState("demo-user-1");
  const normalizedUserId = userId.trim() || "demo-user-1";
  const workspace = useThreadWorkspace(normalizedUserId);

  return (
    <div className="app-shell">
      <aside className="app-rail" aria-label="Commerce Guardian Agent">
        <div className="brand-lockup">
          <span className="brand-mark"><Bot aria-hidden="true" /></span>
          <div><strong>Commerce Guardian Agent</strong><small>可恢复 Agent Runtime</small></div>
        </div>
        <nav className="workspace-navigation" aria-label="工作区导航">
          <button className="active" type="button" aria-current="page">
            <Signal aria-hidden="true" /><span><strong>Agent Threads</strong><small>Thread · Turn · Item</small></span>
          </button>
        </nav>
        <div className="rail-footnote"><Settings2 aria-hidden="true" /><span>Spring AI · SSE · HITL</span></div>
      </aside>
      <main className="app-page">
        <header className="app-topbar">
          <div><span className="topbar-section">AGENT-FIRST WORKSPACE</span><strong>可恢复、可观测、可控的执行上下文</strong></div>
          <details className="session-context">
            <summary><CircleUserRound aria-hidden="true" /><span>认证上下文</span><small>{normalizedUserId}</small></summary>
            <div className="context-fields">
              <label htmlFor="demo-user-id">用户标识
                <input id="demo-user-id" value={userId} onChange={(event) => setUserId(event.target.value)} />
              </label>
              <p className="context-help">生产环境由认证上下文提供；此输入仅用于本地演示。</p>
            </div>
          </details>
        </header>
        <div className="workbench-content"><ThreadWorkspace workspace={workspace} userId={normalizedUserId} /></div>
      </main>
    </div>
  );
}
