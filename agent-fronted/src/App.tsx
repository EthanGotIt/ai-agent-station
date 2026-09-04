import { CircleUserRound, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { ThreadWorkspace } from "./ThreadWorkspace";
import { useThreadWorkspace } from "./useThreadWorkspace";

/** Commerce Guardian Agent 工作区外壳：身份只用于演示认证上下文，业务操作全部落到对话。 */
export function App() {
  const [userId, setUserId] = useState("demo-user-1");
  const normalizedUserId = userId.trim() || "demo-user-1";
  const workspace = useThreadWorkspace(normalizedUserId);

  return (
    <div className="app-shell">
      <div className="app-page">
        <header className="app-topbar">
          <div className="product-lockup"><span className="brand-mark"><ShieldCheck aria-hidden="true" /></span><h1 className="product-name">Commerce Guardian Agent</h1><span className="product-divider" aria-hidden="true" /><span className="console-title">订单调度台</span></div>
          <details className="thread-context">
            <summary><CircleUserRound aria-hidden="true" /><span>当前账户</span><small>{normalizedUserId}</small></summary>
            <div className="context-fields">
              <label htmlFor="demo-user-id">用户标识
                <input id="demo-user-id" value={userId} onChange={(event) => setUserId(event.target.value)} />
              </label>
              <p className="context-help">本地演示使用此账户；生产环境由登录身份自动提供。</p>
            </div>
          </details>
        </header>
        <div className="workbench-content"><ThreadWorkspace workspace={workspace} userId={normalizedUserId} /></div>
      </div>
    </div>
  );
}
