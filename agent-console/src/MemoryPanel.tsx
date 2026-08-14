import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Database, Eye, Pencil, Plus, RefreshCw, Save, Trash2, X } from "lucide-react";
import { isRequestAbort, requestJson } from "./http";
import type { MemoryEntry, MemoryEvidence } from "./types";

const API = "/api/v1/agent/memories";
const KEYS: Record<"PREFERENCE" | "TASK_CONTEXT", string[]> = {
  PREFERENCE: ["response.language", "response.format", "response.detail"],
  TASK_CONTEXT: ["order.id", "refund.reason"]
};

type Props = { userId: string; sessionId: string; disabled: boolean };

function categoryLabel(category: MemoryEntry["category"]) {
  return { PREFERENCE: "回答偏好", TASK_CONTEXT: "任务上下文", LEGACY: "历史记录" }[category];
}

function originLabel(origin: MemoryEntry["origin"]) {
  return { AUTO: "自动生成", MANUAL: "人工维护", LEGACY: "历史来源" }[origin];
}

function readableDate(value: string | null) {
  return value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "长期有效";
}

/** 会话记忆工作区：用列表—详情结构呈现受控记忆、版本与证据。 */
export function MemoryPanel({ userId, sessionId, disabled }: Props) {
  const [entries, setEntries] = useState<MemoryEntry[]>([]);
  const [evidence, setEvidence] = useState<MemoryEvidence[]>([]);
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [category, setCategory] = useState<"PREFERENCE" | "TASK_CONTEXT">("PREFERENCE");
  const [memoryKey, setMemoryKey] = useState("response.language");
  const [memoryValue, setMemoryValue] = useState("");
  const [message, setMessage] = useState("正在准备会话记忆。");
  const [loading, setLoading] = useState(false);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState("");
  const loadControllerRef = useRef<AbortController | null>(null);
  const evidenceControllerRef = useRef<AbortController | null>(null);
  const loadRequestRef = useRef(0);
  const evidenceRequestRef = useRef(0);

  const load = useCallback(async () => {
    if (!userId || !sessionId) {
      setEntries([]);
      setSelectedId(null);
      setEvidence([]);
      setMessage("填写用户和会话 ID 后即可查看记忆。");
      return;
    }
    loadControllerRef.current?.abort();
    const controller = new AbortController();
    const requestNumber = ++loadRequestRef.current;
    loadControllerRef.current = controller;
    setLoading(true);
    try {
      const query = new URLSearchParams({ sessionId, includeDeleted: String(includeDeleted) });
      const loaded = await requestJson<MemoryEntry[]>(`${API}?${query}`, {
        headers: { "X-User-Id": userId }, signal: controller.signal
      });
      if (requestNumber !== loadRequestRef.current) return;
      setEntries(loaded);
      setSelectedId((current) => loaded.some((entry) => entry.entryId === current) ? current : loaded[0]?.entryId ?? null);
      setEvidence([]);
      setMessage(loaded.length > 0 ? `已加载 ${loaded.length} 条受控记忆。` : "这个会话还没有可用记忆。");
    } catch (error) {
      if (requestNumber === loadRequestRef.current && !isRequestAbort(error)) {
        setMessage(error instanceof Error ? error.message : "记忆加载失败");
      }
    } finally {
      if (requestNumber === loadRequestRef.current) setLoading(false);
    }
  }, [includeDeleted, sessionId, userId]);

  useEffect(() => {
    void load();
    return () => loadControllerRef.current?.abort();
  }, [load]);

  useEffect(() => () => evidenceControllerRef.current?.abort(), []);

  const create = useCallback(async (event: FormEvent) => {
    event.preventDefault();
    if (!userId || !sessionId || !memoryValue.trim() || pendingAction) return;
    setPendingAction("create");
    try {
      await requestJson(API, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({ sessionId, category, memoryKey, value: memoryValue.trim() })
      });
      setMemoryValue("");
      setCreateOpen(false);
      await load();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "记忆创建失败");
    } finally {
      setPendingAction(null);
    }
  }, [category, load, memoryKey, memoryValue, pendingAction, sessionId, userId]);

  const remove = useCallback(async (entry: MemoryEntry) => {
    if (pendingAction) return;
    setPendingAction(`remove:${entry.entryId}`);
    try {
      const query = new URLSearchParams({ sessionId, expectedVersion: String(entry.version) });
      await requestJson(`${API}/${encodeURIComponent(entry.entryId)}?${query}`, {
        method: "DELETE", headers: { "X-User-Id": userId }
      });
      setEditingId(null);
      await load();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "记忆删除失败");
    } finally {
      setPendingAction(null);
    }
  }, [load, pendingAction, sessionId, userId]);

  const edit = useCallback(async (entry: MemoryEntry) => {
    const next = editValue.trim();
    if (pendingAction || !next) return;
    setPendingAction(`edit:${entry.entryId}`);
    try {
      await requestJson(`${API}/${encodeURIComponent(entry.entryId)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({
          sessionId, category: entry.category, memoryKey: entry.memoryKey,
          value: next, expectedVersion: entry.version, expiresAt: entry.expiresAt
        })
      });
      setEditingId(null);
      await load();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "记忆编辑失败");
    } finally {
      setPendingAction(null);
    }
  }, [editValue, load, pendingAction, sessionId, userId]);

  const showEvidence = useCallback(async (entry: MemoryEntry) => {
    if (pendingAction) return;
    evidenceControllerRef.current?.abort();
    const controller = new AbortController();
    const requestNumber = ++evidenceRequestRef.current;
    evidenceControllerRef.current = controller;
    setPendingAction(`evidence:${entry.entryId}`);
    try {
      const query = new URLSearchParams({ sessionId });
      const loaded = await requestJson<MemoryEvidence[]>(
        `${API}/${encodeURIComponent(entry.entryId)}/evidence?${query}`,
        { headers: { "X-User-Id": userId }, signal: controller.signal }
      );
      if (requestNumber !== evidenceRequestRef.current) return;
      setEvidence(loaded);
      setMessage(`正在显示 ${entry.memoryKey} 的 ${loaded.length} 条证据。`);
    } catch (error) {
      if (requestNumber === evidenceRequestRef.current && !isRequestAbort(error)) {
        setMessage(error instanceof Error ? error.message : "证据加载失败");
      }
    } finally {
      if (requestNumber === evidenceRequestRef.current) setPendingAction(null);
    }
  }, [pendingAction, sessionId, userId]);

  const busy = disabled || loading || pendingAction !== null;
  const selected = entries.find((entry) => entry.entryId === selectedId) ?? null;

  function selectEntry(entry: MemoryEntry) {
    evidenceControllerRef.current?.abort();
    setSelectedId(entry.entryId);
    setEvidence([]);
    setEditingId(null);
  }

  return <section className="memory-workspace" aria-labelledby="memory-heading" aria-busy={busy}>
    <div className="workspace-heading">
      <div><h1 id="memory-heading">可审计会话记忆</h1><p>受控偏好与任务上下文只在当前用户和会话边界内生效。</p></div>
      <div className="workspace-actions"><button className="secondary icon-button" type="button" disabled={busy || !userId || !sessionId} onClick={() => void load()}><RefreshCw aria-hidden="true" />刷新</button><button className="icon-button" type="button" disabled={busy || !userId || !sessionId} onClick={() => setCreateOpen((current) => !current)}><Plus aria-hidden="true" />创建记忆</button></div>
    </div>
    <div className="memory-toolbar"><label className="inline-check"><input type="checkbox" checked={includeDeleted} disabled={busy} onChange={(event) => setIncludeDeleted(event.target.checked)} /><span>显示已删除条目</span></label><p role="status">{loading ? "正在加载记忆…" : message}</p></div>
    {createOpen ? <form className="memory-create" onSubmit={(event) => void create(event)}>
      <label>类别<select value={category} disabled={busy} onChange={(event) => { const next = event.target.value as "PREFERENCE" | "TASK_CONTEXT"; setCategory(next); setMemoryKey(KEYS[next][0]); }}><option value="PREFERENCE">回答偏好</option><option value="TASK_CONTEXT">任务上下文</option></select></label>
      <label>受控键<select value={memoryKey} disabled={busy} onChange={(event) => setMemoryKey(event.target.value)}>{KEYS[category].map((key) => <option key={key} value={key}>{key}</option>)}</select></label>
      <label>值<input value={memoryValue} disabled={busy} placeholder="受控格式的值" onChange={(event) => setMemoryValue(event.target.value)} /></label>
      <div className="memory-create-actions"><button className="secondary icon-button" type="button" disabled={busy} onClick={() => setCreateOpen(false)}><X aria-hidden="true" />取消</button><button className="icon-button" disabled={busy || !memoryValue.trim()} type="submit"><Save aria-hidden="true" />保存</button></div>
    </form> : null}
    <div className="memory-layout">
      <section className="memory-list-pane" aria-label="会话记忆列表">
        {loading ? <div className="memory-skeleton" aria-label="正在加载记忆"><span /><span /><span /></div> : null}
        {!loading && entries.length === 0 ? <div className="memory-empty"><Database aria-hidden="true" /><p>暂时没有可显示的会话记忆。你可以创建一条受控偏好，或在 Agent 请求中启用后台生成。</p></div> : null}
        <div className="memory-list">
          {entries.map((entry) => <button type="button" className={`memory-entry ${selected?.entryId === entry.entryId ? "selected" : ""}`} key={entry.entryId} onClick={() => selectEntry(entry)} aria-pressed={selected?.entryId === entry.entryId}>
            <span><strong>{entry.memoryKey}</strong><small>{categoryLabel(entry.category)} · {originLabel(entry.origin)}</small></span><em>{entry.deleted ? "已删除" : `v${entry.version}`}</em>
          </button>)}
        </div>
      </section>
      <section className="memory-detail-pane" aria-labelledby="memory-detail-heading">
        {selected ? <>
          <div className="detail-heading"><div><h2 id="memory-detail-heading">{selected.memoryKey}</h2><p>{categoryLabel(selected.category)} · {originLabel(selected.origin)} · 版本 {selected.version}</p></div><span className={`memory-state ${selected.deleted ? "deleted" : ""}`}>{selected.deleted ? "已删除" : "有效"}</span></div>
          {editingId === selected.entryId ? <form className="memory-edit" onSubmit={(event) => { event.preventDefault(); void edit(selected); }}><label>记忆值<textarea value={editValue} disabled={busy} onChange={(event) => setEditValue(event.target.value)} /></label><div className="actions"><button className="secondary icon-button" type="button" disabled={busy} onClick={() => setEditingId(null)}><X aria-hidden="true" />取消</button><button className="icon-button" disabled={busy || !editValue.trim()} type="submit"><Save aria-hidden="true" />保存修改</button></div></form> : <div className="memory-value"><strong>当前值</strong><p>{selected.value}</p></div>}
          <dl className="field-list memory-details"><div><dt>到期时间</dt><dd>{readableDate(selected.expiresAt)}</dd></div><div><dt>最近更新</dt><dd>{readableDate(selected.updatedAt)}</dd></div><div><dt>置信度</dt><dd>{selected.confidence.toFixed(2)}</dd></div><div><dt>来源 ID</dt><dd>{selected.sourceId ?? "人工维护"}</dd></div></dl>
          <div className="actions memory-detail-actions"><button className="secondary icon-button" type="button" disabled={busy} onClick={() => void showEvidence(selected)}><Eye aria-hidden="true" />查看证据</button>{!selected.deleted ? <><button className="secondary icon-button" type="button" disabled={busy} onClick={() => { setEditingId(selected.entryId); setEditValue(selected.value); }}><Pencil aria-hidden="true" />编辑</button><button className="danger icon-button" type="button" disabled={busy} onClick={() => void remove(selected)}><Trash2 aria-hidden="true" />删除</button></> : null}</div>
          {evidence.length > 0 ? <section className="evidence-section" aria-labelledby="evidence-heading"><h3 id="evidence-heading">证据</h3><ul className="evidence-list">{evidence.map((item) => <li key={item.evidenceId}><strong>{item.evidenceType}</strong><span>{item.evidenceRef}</span><time dateTime={item.createdAt}>{readableDate(item.createdAt)}</time></li>)}</ul></section> : null}
        </> : <div className="detail-empty"><Database aria-hidden="true" /><h2 id="memory-detail-heading">选择一条会话记忆</h2><p>这里会显示受控值、版本、证据和可用操作。</p></div>}
      </section>
    </div>
  </section>;
}
