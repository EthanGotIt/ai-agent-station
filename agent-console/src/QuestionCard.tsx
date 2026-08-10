import { useState, type FormEvent } from "react";
import type { WorkflowQuestionEvent } from "./types";

type Props = {
  value: WorkflowQuestionEvent;
  disabled: boolean;
  onSubmit: (answers: Record<string, string>) => void;
};

export function QuestionCard({ value, disabled, onSubmit }: Props) {
  const { question, workflowRun } = value;
  const [answers, setAnswers] = useState<Record<string, string>>(() => Object.fromEntries(
    question.fields.flatMap((field) => field.suggestion ? [[field.name, field.suggestion.value]] : [])
  ));

  function setAnswer(name: string, answer: string) {
    setAnswers((current) => ({ ...current, [name]: answer }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit(answers);
  }

  return <section className="card question-card">
    <p className="eyebrow">Workflow / {workflowRun.status}</p>
    <h2>{question.title}</h2>
    <p>{question.prompt}</p>
    <form onSubmit={submit}>
      {question.fields.map((field) => <label key={field.name}>
        <span>{field.label}{field.required ? " *" : ""}</span>
        {field.type === "SINGLE_SELECT" || field.type === "CONFIRM" ? <select
          required={field.required}
          value={answers[field.name] ?? ""}
          disabled={disabled}
          onChange={(event) => setAnswer(field.name, event.target.value)}
        >
          <option value="">请选择</option>
          {field.options.map((option) => <option value={option} key={option}>{option}</option>)}
        </select> : <input
          required={field.required}
          minLength={question.cardType === "refund_description_question" ? 10 : undefined}
          maxLength={question.cardType === "refund_description_question" ? 500 : undefined}
          value={answers[field.name] ?? ""}
          disabled={disabled}
          onChange={(event) => setAnswer(field.name, event.target.value)}
        />}
        {field.suggestion ? <small>建议值来自记忆：{field.suggestion.value}（不会自动提交）</small> : null}
      </label>)}
      <button disabled={disabled} type="submit">提交答案</button>
    </form>
  </section>;
}
