import { useCallback, useEffect, useState } from "react";
import { readJsonResponse } from "./http";
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

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    const query = new URLSearchParams({ page: String(page), size: "20" });
    if (status) query.set("status", status);
    try {
      const response = await fetch(`${API}?${query}`, { headers: { "X-Operator-Id": operatorId.trim() } });
      const next = await readJsonResponse<AfterSalesCasePage>(response);
      setData(next);
      setSelected((current) => next.items.find((item) => item.caseId === current?.caseId) ?? null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "审核队列暂时无法加载，请稍后刷新。");
    } finally {
      setLoading(false);
    }
  }, [operatorId, page, status]);

  useEffect(() => { void load(); }, [load]);

  const select = useCallback(async (caseId: string) => {
    setError("");
    try {
      const response = await fetch(`${API}/${encodeURIComponent(caseId)}`, {
        headers: { "X-Operator-Id": operatorId.trim() }
      });
      setSelected(await readJsonResponse<AfterSalesCase>(response));
      setNote("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "售后详情暂时无法加载，请稍后重试。");
    }
  }, [operatorId]);

  const decide = useCallback(async (decision: Decision) => {
    if (!selected || saving || !operatorId.trim()) return;
    if (decision === "REJECT" && !note.trim()) {
      setError("请填写驳回说明，方便用户了解下一步。");
      return;
    }
    setSaving(true);
    setError("");
    try {
      const response = await fetch(`${API}/${encodeURIComponent(selected.caseId)}/review-decisions`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Operator-Id": operatorId.trim() },
        body: JSON.stringify({ decisionId: crypto.randomUUID(), expectedVersion: selected.version, decision, note })
      });
      const result = await readJsonResponse<{ caseModel: AfterSalesCase }>(response);
      setSelected(result.caseModel);
      setData((current) => current ? { ...current, items: updateCase(current.items, result.caseModel) } : current);
      setNote("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "审核提交失败，请刷新后重试。");
    } finally {
      setSaving(false);
    }
  }, [note, operatorId, saving, selected]);

  const retry = useCallback(async () => {
    if (!selected || saving || !operatorId.trim()) return;
    setSaving(true);
    setError("");
    try {
      const response = await fetch(`${API}/${encodeURIComponent(selected.caseId)}/refund-retries`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Operator-Id": operatorId.trim() },
        body: JSON.stringify({ retryId: crypto.randomUUID(), expectedVersion: selected.version })
      });
      const result = await readJsonResponse<{ caseModel: AfterSalesCase }>(response);
      setSelected(result.caseModel);
      setData((current) => current ? { ...current, items: updateCase(current.items, result.caseModel) } : current);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "退款重试提交失败，请刷新后重试。");
    } finally {
      setSaving(false);
    }
  }, [operatorId, saving, selected]);

  const items = data?.items ?? [];
  const canReview = selected?.status === "PENDING_REVIEW";
  const canRetry = selected?.status === "REFUND_FAILED";

  return <section className="review-panel" aria-labelledby="review-heading">
    <div className="review-heading">
      <div><h2 id="review-heading">售后审核队列</h2><p>审核人工申请，追踪异步退款的最终状态。</p></div>
      <button className="secondary" type="button" disabled={loading || saving} onClick={() => void load()}>刷新</button>
    </div>
    <label className="review-filter">状态筛选
      <select value={status} disabled={loading || saving} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
        {STATUS_OPTIONS.map(([value, label]) => <option value={value} key={value}>{label}</option>)}
      </select>
    </label>
    {error ? <p className="review-error" role="alert">{error}</p> : null}
    {loading ? <p className="muted review-loading">正在更新审核队列…</p> : null}
    {!loading && items.length === 0 ? <p className="review-empty">当前筛选下没有售后申请。新的人工审核申请会显示在这里。</p> : null}
    <div className="review-list" aria-live="polite">
      {items.map((item) => <button className={`review-row ${selected?.caseId === item.caseId ? "selected" : ""}`} type="button"
        key={item.caseId} onClick={() => void select(item.caseId)} aria-pressed={selected?.caseId === item.caseId}>
        <span><strong>{item.orderId}</strong><small>{money(item.amount, item.currency)}</small></span>
        <span className={`status status-${item.status.toLowerCase()}`}>{statusLabel(item.status)}</span>
      </button>)}
    </div>
    {data && (page > 0 || data.hasNext) ? <div className="review-pagination">
      <button className="secondary" type="button" disabled={page === 0 || loading} onClick={() => setPage((current) => current - 1)}>上一页</button>
      <span>第 {page + 1} 页</span>
      <button className="secondary" type="button" disabled={!data.hasNext || loading} onClick={() => setPage((current) => current + 1)}>下一页</button>
    </div> : null}
    {selected ? <section className="review-detail" aria-labelledby="review-detail-heading">
      <h3 id="review-detail-heading">申请详情</h3>
      <dl className="field-list">
        <div><dt>售后单</dt><dd>{selected.caseId}</dd></div>
        <div><dt>处理状态</dt><dd>{statusLabel(selected.status)}</dd></div>
        <div><dt>退款原因</dt><dd>{selected.reason}</dd></div>
        <div><dt>退款金额</dt><dd>{money(selected.amount, selected.currency)}</dd></div>
        <div><dt>退款任务</dt><dd>{selected.refundCommand?.status ?? "等待审核"}</dd></div>
        <div><dt>尝试次数</dt><dd>{selected.refundCommand?.attemptCount ?? 0}</dd></div>
      </dl>
      <p className="review-description">{selected.description || "用户未补充退款说明。"}</p>
      {selected.failureCode ? <p className="review-error">失败原因：{selected.failureCode}</p> : null}
      {canReview ? <>
        <label>审核说明（驳回时必填）
          <textarea value={note} disabled={saving} onChange={(event) => setNote(event.target.value)} maxLength={500}
            placeholder="例如：请在签收后提供商品问题凭证。" />
        </label>
        <div className="actions review-actions">
          <button type="button" disabled={saving || !operatorId.trim()} onClick={() => void decide("APPROVE")}>批准并创建退款任务</button>
          <button className="danger" type="button" disabled={saving || !operatorId.trim() || !note.trim()} onClick={() => void decide("REJECT")}>驳回申请</button>
        </div>
      </> : null}
      {canRetry ? <div className="actions review-actions">
        <button type="button" disabled={saving || !operatorId.trim()} onClick={() => void retry()}>重新发起退款</button>
      </div> : null}
    </section> : null}
  </section>;
}
