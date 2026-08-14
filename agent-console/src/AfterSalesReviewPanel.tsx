import { CircleAlert, ClipboardList, RefreshCw, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { HttpRequestError, isRequestAbort, requestJson } from "./http";
import type { AfterSalesCase, AfterSalesCasePage } from "./types";

const API = "/api/v1/after-sales/cases";

const STATUS_OPTIONS = [
  ["", "全部状态"],
  ["PENDING_REVIEW", "待审核"],
  ["REFUND_PROCESSING", "退款处理中"],
  ["REFUND_FAILED", "退款失败"],
  ["COMPLETED", "已完成"],
  ["REJECTED", "已驳回"]
] as const;

type Props = { operatorId: string };
type Decision = "APPROVE" | "REJECT";

function statusLabel(status: AfterSalesCase["status"]) {
  const labels: Record<AfterSalesCase["status"], string> = {
    PENDING_REVIEW: "待审核",
    REFUND_PROCESSING: "退款处理中",
    COMPLETED: "已完成",
    REFUND_FAILED: "退款失败",
    REJECTED: "已驳回"
  };
  return labels[status];
}

function money(value: number | null, currency: string) {
  return value === null ? "金额待确认" : `${value.toFixed(2)} ${currency}`;
}

function updateCase(items: AfterSalesCase[], updated: AfterSalesCase) {
  return items.map((item) => item.caseId === updated.caseId ? updated : item);
}

export function AfterSalesReviewPanel({ operatorId }: Props) {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AfterSalesCasePage | null>(null);
  const [selected, setSelected] = useState<AfterSalesCase | null>(null);
  const [note, setNote] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const listControllerRef = useRef<AbortController | null>(null);
  const detailControllerRef = useRef<AbortController | null>(null);
  const listRequestRef = useRef(0);
  const detailRequestRef = useRef(0);
  const actionIdsRef = useRef(new Map<string, string>());

  const clearCaseActionIds = useCallback((caseId: string) => {
    for (const key of actionIdsRef.current.keys()) {
      if (key.includes(`:${caseId}:`)) actionIdsRef.current.delete(key);
    }
  }, []);

  const load = useCallback(async () => {
    const normalizedOperatorId = operatorId.trim();
    listControllerRef.current?.abort();
    if (!normalizedOperatorId) {
      setData(null);
      setSelected(null);
      setLoading(false);
      setError("");
      return;
    }
    const controller = new AbortController();
    const requestNumber = ++listRequestRef.current;
    listControllerRef.current = controller;
    setLoading(true);
    setError("");
    const query = new URLSearchParams({ page: String(page), size: "20" });
    if (status) query.set("status", status);
    try {
      const next = await requestJson<AfterSalesCasePage>(`${API}?${query}`, {
        headers: { "X-Operator-Id": normalizedOperatorId }, signal: controller.signal
      });
      if (requestNumber !== listRequestRef.current) return;
      setData(next);
      setSelected((current) => {
        const refreshed = next.items.find((item) => item.caseId === current?.caseId) ?? null;
        if (current && refreshed && refreshed.version !== current.version) clearCaseActionIds(current.caseId);
        if (current && refreshed && current.version >= refreshed.version) return current;
        return refreshed;
      });
    } catch (failure) {
      if (requestNumber === listRequestRef.current && !isRequestAbort(failure)) {
        setError(failure instanceof Error ? failure.message : "审核队列暂时无法加载，请稍后刷新。");
      }
    } finally {
      if (requestNumber === listRequestRef.current) setLoading(false);
    }
  }, [clearCaseActionIds, operatorId, page, status]);

  useEffect(() => {
    void load();
    return () => listControllerRef.current?.abort();
  }, [load]);

  useEffect(() => () => detailControllerRef.current?.abort(), []);

  const select = useCallback(async (caseId: string, refreshedMessage = "") => {
    const normalizedOperatorId = operatorId.trim();
    if (!normalizedOperatorId) return;
    detailControllerRef.current?.abort();
    const controller = new AbortController();
    const requestNumber = ++detailRequestRef.current;
    detailControllerRef.current = controller;
    setError("");
    try {
      const next = await requestJson<AfterSalesCase>(`${API}/${encodeURIComponent(caseId)}`, {
        headers: { "X-Operator-Id": normalizedOperatorId }, signal: controller.signal
      });
      if (requestNumber !== detailRequestRef.current) return;
      setSelected((current) => {
        if (current?.caseId === next.caseId && current.version !== next.version) clearCaseActionIds(next.caseId);
        return next;
      });
      setNote("");
      if (refreshedMessage) setError(refreshedMessage);
    } catch (failure) {
      if (requestNumber === detailRequestRef.current && !isRequestAbort(failure)) {
        setError(failure instanceof Error ? failure.message : "售后详情暂时无法加载，请稍后重试。");
      }
    }
  }, [clearCaseActionIds, operatorId]);

  const actionId = useCallback((key: string) => {
    const existing = actionIdsRef.current.get(key);
    if (existing) return existing;
    const next = crypto.randomUUID();
    actionIdsRef.current.set(key, next);
    return next;
  }, []);

  const decide = useCallback(async (decision: Decision) => {
    if (!selected || saving || !operatorId.trim()) return;
    const trimmedNote = note.trim();
    if (decision === "REJECT" && !trimmedNote) {
      setError("请填写驳回说明，方便用户了解下一步。");
      return;
    }
    const key = `review:${selected.caseId}:${selected.version}:${decision}:${trimmedNote}`;
    setSaving(true);
    setError("");
    try {
      const result = await requestJson<{ caseModel: AfterSalesCase }>(
        `${API}/${encodeURIComponent(selected.caseId)}/review-decisions`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Operator-Id": operatorId.trim() },
          body: JSON.stringify({
            decisionId: actionId(key), expectedVersion: selected.version, decision, note: trimmedNote
          })
        }
      );
      clearCaseActionIds(selected.caseId);
      setSelected(result.caseModel);
      setData((current) => current ? { ...current, items: updateCase(current.items, result.caseModel) } : current);
      setNote("");
    } catch (failure) {
      if (failure instanceof HttpRequestError && failure.status === 409) {
        await select(selected.caseId, "售后申请状态已变化，已刷新详情。");
      } else {
        setError(failure instanceof Error ? failure.message : "审核提交失败，请刷新后重试。");
      }
    } finally {
      setSaving(false);
    }
  }, [actionId, clearCaseActionIds, note, operatorId, saving, select, selected]);

  const retry = useCallback(async () => {
    if (!selected || saving || !operatorId.trim()) return;
    const key = `retry:${selected.caseId}:${selected.version}`;
    setSaving(true);
    setError("");
    try {
      const result = await requestJson<{ caseModel: AfterSalesCase }>(
        `${API}/${encodeURIComponent(selected.caseId)}/refund-retries`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Operator-Id": operatorId.trim() },
          body: JSON.stringify({ retryId: actionId(key), expectedVersion: selected.version })
        }
      );
      clearCaseActionIds(selected.caseId);
      setSelected(result.caseModel);
      setData((current) => current ? { ...current, items: updateCase(current.items, result.caseModel) } : current);
    } catch (failure) {
      if (failure instanceof HttpRequestError && failure.status === 409) {
        await select(selected.caseId, "售后申请状态已变化，已刷新详情。");
      } else {
        setError(failure instanceof Error ? failure.message : "退款重试提交失败，请刷新后重试。");
      }
    } finally {
      setSaving(false);
    }
  }, [actionId, clearCaseActionIds, operatorId, saving, select, selected]);

  const items = data?.items ?? [];
  const canReview = selected?.status === "PENDING_REVIEW";
  const canRetry = selected?.status === "REFUND_FAILED";

  return <section className="after-sales-workspace" aria-labelledby="after-sales-heading" aria-busy={loading || saving}>
    <div className="workspace-heading">
      <div><h1 id="after-sales-heading">售后审核与退款处理</h1><p>从人工审核到异步退款结果，所有状态都保持可追溯。</p></div>
      <button className="secondary icon-button" type="button" disabled={loading || saving || !operatorId.trim()} onClick={() => void load()}><RefreshCw aria-hidden="true" />刷新队列</button>
    </div>
    {error ? <p className="review-error" role="alert"><CircleAlert aria-hidden="true" />{error}</p> : null}
    <div className="review-workspace">
      <section className="review-queue" aria-labelledby="review-heading">
        <div className="queue-heading"><ClipboardList aria-hidden="true" /><div><h2 id="review-heading">审核队列</h2><p>选择一条申请后在右侧完成审核或重试。</p></div></div>
        <label className="review-filter">状态筛选
          <select value={status} disabled={loading || saving} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
            {STATUS_OPTIONS.map(([value, label]) => <option value={value} key={value}>{label}</option>)}
          </select>
        </label>
        {loading ? <div className="review-skeleton" aria-label="正在加载审核队列"><span /><span /><span /></div> : null}
        {!loading && items.length === 0 && operatorId.trim() ? <p className="review-empty">当前筛选下没有售后申请。新的人工审核申请会显示在这里。</p> : null}
        <div className="review-list" aria-live="polite">
          {items.map((item) => <button className={`review-row ${selected?.caseId === item.caseId ? "selected" : ""}`} type="button"
            key={item.caseId} onClick={() => void select(item.caseId)} aria-pressed={selected?.caseId === item.caseId}>
            <span><strong>{item.orderId}</strong><small>{money(item.amount, item.currency)}</small></span>
            <span className={`status status-${item.status.toLowerCase()}`}>{statusLabel(item.status)}</span>
          </button>)}
        </div>
        {data && (page > 0 || data.hasNext) ? <div className="review-pagination">
          <button className="secondary" type="button" disabled={page === 0 || loading || saving} onClick={() => setPage((current) => current - 1)}>上一页</button>
          <span>第 {page + 1} 页</span>
          <button className="secondary" type="button" disabled={!data.hasNext || loading || saving} onClick={() => setPage((current) => current + 1)}>下一页</button>
        </div> : null}
      </section>
      <section className="review-detail-pane" aria-labelledby="review-detail-heading">
        {selected ? <>
          <div className="detail-heading"><div><h2 id="review-detail-heading">{selected.orderId}</h2><p>售后单 {selected.caseId}</p></div><span className={`status status-${selected.status.toLowerCase()}`}>{statusLabel(selected.status)}</span></div>
          <dl className="field-list">
            <div><dt>退款原因</dt><dd>{selected.reason}</dd></div>
            <div><dt>退款金额</dt><dd>{money(selected.amount, selected.currency)}</dd></div>
            <div><dt>退款任务</dt><dd>{selected.refundCommand?.status ?? "等待审核"}</dd></div>
            <div><dt>尝试次数</dt><dd>{selected.refundCommand?.attemptCount ?? 0}</dd></div>
          </dl>
          <div className="detail-notes"><strong>用户说明</strong><p className="review-description">{selected.description || "用户未补充退款说明。"}</p></div>
          {selected.failureCode ? <p className="review-error"><CircleAlert aria-hidden="true" />失败原因：{selected.failureCode}</p> : null}
          {canReview ? <div className="review-decision">
            <label>审核说明（驳回时必填）
              <textarea value={note} disabled={saving} onChange={(event) => setNote(event.target.value)} maxLength={500}
                placeholder="例如：请在签收后提供商品问题凭证。" />
            </label>
            <div className="actions review-actions">
              <button type="button" disabled={saving || !operatorId.trim()} onClick={() => void decide("APPROVE")}>批准并创建退款任务</button>
              <button className="danger" type="button" disabled={saving || !operatorId.trim() || !note.trim()} onClick={() => void decide("REJECT")}>驳回申请</button>
            </div>
          </div> : null}
          {canRetry ? <div className="review-decision"><p>退款任务已达到自动重试上限。确认后会按当前案件版本重新排队。</p><div className="actions review-actions">
            <button type="button" disabled={saving || !operatorId.trim()} onClick={() => void retry()}><RotateCcw aria-hidden="true" />重新发起退款</button>
          </div></div> : null}
        </> : <div className="detail-empty"><ClipboardList aria-hidden="true" /><h2 id="review-detail-heading">选择一条售后申请</h2><p>审核说明、退款任务和尝试记录会在这里形成完整的处理上下文。</p></div>}
      </section>
    </div>
  </section>;
}
