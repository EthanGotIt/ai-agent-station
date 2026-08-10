import type { ReactNode } from "react";

type CardData = Record<string, unknown>;
type Props = { data: unknown };

function value(data: CardData, name: string) {
  const current = data[name];
  return current === null || current === undefined || current === "" ? "—" : String(current);
}

function Fields({ data, names }: { data: CardData; names: Array<[string, string]> }) {
  return <dl className="field-list">
    {names.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{value(data, key)}</dd></div>)}
  </dl>;
}

function OrderOverview({ data }: { data: CardData }) {
  return <Fields data={data} names={[
    ["orderId", "订单号"], ["status", "订单状态"], ["amount", "金额"],
    ["createdAt", "创建时间"], ["estimatedDeliveryAt", "预计送达"]
  ]} />;
}

function LogisticsTimeline({ data }: { data: CardData }) {
  const events = Array.isArray(data.events) ? data.events as CardData[] : [];
  return <>
    <Fields data={data} names={[["orderId", "订单号"], ["estimatedDeliveryAt", "预计送达"]]} />
    <ol className="timeline-list">
      {events.map((event, index) => <li key={`${value(event, "occurredAt")}-${index}`}>
        <strong>{value(event, "status")}</strong><span>{value(event, "occurredAt")}</span>
        <p>{value(event, "location")} · {value(event, "description")}</p>
      </li>)}
    </ol>
  </>;
}

function Diagnosis({ data }: { data: CardData }) {
  return <>
    <Fields data={data} names={[["orderId", "订单号"], ["diagnosisType", "诊断"], ["suggestedAction", "建议动作"]]} />
    {Array.isArray(data.evidence) ? <ul>{(data.evidence as CardData[]).map((item, index) =>
      <li key={index}>{value(item, "field")}: {value(item, "value")}</li>)}</ul> : null}
  </>;
}

function AfterSales({ data }: { data: CardData }) {
  return <Fields data={data} names={[
    ["orderId", "订单号"], ["caseId", "售后单"], ["status", "状态"],
    ["handlingMode", "处理方式"], ["refundId", "退款单"], ["amount", "退款金额"]
  ]} />;
}

export function StructuredCard({ data }: Props) {
  if (!data || typeof data !== "object") return null;
  const result = data as { cardType?: string; data?: CardData };
  const card = result.data ?? result as CardData;
  const cardType = result.cardType ?? "structured_result";
  let content: ReactNode;
  switch (cardType) {
    case "order_overview": content = <OrderOverview data={card} />; break;
    case "logistics_timeline": content = <LogisticsTimeline data={card} />; break;
    case "order_diagnosis": content = <Diagnosis data={card} />; break;
    case "after_sales_result":
    case "after_sales_status":
    case "after_sales_confirmation": content = <AfterSales data={card} />; break;
    default: content = <pre>{JSON.stringify(card, null, 2)}</pre>;
  }
  return <article className="structured-card">
    <strong>{cardType}</strong>
    {content}
  </article>;
}
