import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { isRequestAbort, requestJson } from "./http";
import type { MemoryEntry, MemoryEvidence } from "./types";

const API = "/api/v1/agent/memories";
const KEYS: Record<"PREFERENCE" | "TASK_CONTEXT", string[]> = {
  PREFERENCE: ["response.language", "response.format", "response.detail"],
  TASK_CONTEXT: ["order.id", "refund.reason"]
};

type Props = { userId: string; sessionId: string; disabled: boolean };

export function MemoryPanel({ userId, sessionId, disabled }: Props) {
  const [entries, setEntries] = useState<MemoryEntry[]>([]);
  const [evidence, setEvidence] = useState<MemoryEvidence[]>([]);
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [category, setCategory] = useState<"PREFERENCE" | "TASK_CONTEXT">("PREFERENCE");
  const [memoryKey, setMemoryKey] = useState("response.language");
  const [memoryValue, setMemoryValue] = useState("");
  const [message, setMessage] = useState("尚未加载记忆。");
  const [loading, setLoading] = useState(false);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const loadControllerRef = useRef<AbortController | null>(null);
  const evidenceControllerRef = useRef<AbortController | null>(null);
  const loadRequestRef = useRef(0);
  const evidenceRequestRef = useRef(0);

  const load = useCallback(async () => {
    if (!userId || !sessionId) return;
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
      setEvidence([]);
      setMessage(`已加载 ${loaded.length} 条记忆。`);
    } catch (error) {
      if (requestNumber === loadRequestRef.current && !isRequestAbort(error)) {
        setMessage(error instanceof Error ? error.message : "记忆加载失败");
      }
    } finally {
      if (requestNumber === loadRequestRef.current) setLoading(false);
    }
  }, [includeDeleted, sessionId, userId]);

  useEffect(() => () => {
    loadControllerRef.current?.abort();
    evidenceControllerRef.current?.abort();
  }, []);

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
      await load();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "记忆删除失败");
    } finally {
      setPendingAction(null);
    }
  }, [load, pendingAction, sessionId, userId]);

  const edit = useCallback(async (entry: MemoryEntry) => {
    if (pendingAction) return;
    const next = window.prompt("修改记忆值", entry.value);
    if (next === null || !next.trim()) return;
    setPendingAction(`edit:${entry.entryId}`);
    try {
      await requestJson(`${API}/${encodeURIComponent(entry.entryId)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-User-Id": userId },
        body: JSON.stringify({
          sessionId, category: entry.category, memoryKey: entry.memoryKey,
          value: next.trim(), expectedVersion: entry.version, expiresAt: entry.expiresAt
        })
      });
      await load();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "记忆编辑失败");
    } finally {
      setPendingAction(null);
    }
  }, [load, pendingAction, sessionId, userId]);

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

  return <section className="card memory-panel" aria-busy={busy}>
    <div className="actions"><div><p className="eyebrow">Session Memory</p><h2>可审计会话记忆</h2></div>
      <button className="secondary" disabled={busy || !userId || !sessionId} onClick={() => void load()}>刷新</button></div>
    <label className="inline-check"><input type="checkbox" checked={includeDeleted} disabled={busy}
      onChange={(event) => setIncludeDeleted(event.target.checked)} /> 显示已删除条目</label>
    <form className="memory-create" onSubmit={(event) => void create(event)}>
      <select value={category} disabled={busy} onChange={(event) => {
        const next = event.target.value as "PREFERENCE" | "TASK_CONTEXT";
        setCategory(next); setMemoryKey(KEYS[next][0]);
      }}>
        <option value="PREFERENCE">回答偏好</option><option value="TASK_CONTEXT">任务上下文</option>
      </select>
      <select value={memoryKey} disabled={busy} onChange={(event) => setMemoryKey(event.target.value)}>
        {KEYS[category].map((key) => <option key={key} value={key}>{key}</option>)}
      </select>
      <input value={memoryValue} disabled={busy} placeholder="受控格式的值" onChange={(event) => setMemoryValue(event.target.value)} />
      <button disabled={busy || !memoryValue.trim() || !userId || !sessionId} type="submit">创建</button>
    </form>
    <p className="muted" role="status">{loading ? "正在加载记忆…" : message}</p>
    <div className="memory-list">
      {entries.map((entry) => <article className="memory-entry" key={entry.entryId}>
        <strong>{entry.memoryKey}</strong><span>{entry.value}</span>
        <small>{entry.origin} · v{entry.version} · {entry.deleted ? "已删除" : "有效"}</small>
        <div className="actions">
          <button className="secondary" disabled={busy} onClick={() => void showEvidence(entry)}>证据</button>
          {!entry.deleted ? <><button className="secondary" disabled={busy} onClick={() => void edit(entry)}>编辑</button>
            <button className="secondary" disabled={busy} onClick={() => void remove(entry)}>删除</button></> : null}
        </div>
      </article>)}
    </div>
    {evidence.length > 0 ? <ul className="evidence-list">{evidence.map((item) =>
      <li key={item.evidenceId}>{item.evidenceType} · {item.createdAt}</li>)}</ul> : null}
  </section>;
}
