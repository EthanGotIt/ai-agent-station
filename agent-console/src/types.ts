export type MemoryOptions = { generate?: boolean; use?: boolean };

export type WorkspaceId = "agent" | "after-sales" | "memory";

export type ScenarioDefinition = {
  id: "order" | "logistics" | "refund" | "preference";
  title: string;
  description: string;
  message: string;
};

export type RunTraceEvent = {
  id: string;
  type: "route" | "node" | "progress" | "tool" | "done" | "error";
  data: string;
  at: string;
};

export type ConversationTurnStatus = "running" | "waiting" | "completed" | "cancelled" | "failed";

export type ConversationTurn = {
  id: string;
  requestId: string;
  userMessage: string;
  content: string;
  result: unknown | null;
  error: string | null;
  route: string | null;
  status: ConversationTurnStatus;
  startedAt: string;
  finishedAt: string | null;
};

export type MemoryEntry = {
  entryId: string;
  sourceId: string | null;
  sessionId: string;
  category: "PREFERENCE" | "TASK_CONTEXT" | "LEGACY";
  memoryKey: string;
  value: string;
  origin: "AUTO" | "MANUAL" | "LEGACY";
  confidence: number;
  version: number;
  deleted: boolean;
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type MemoryEvidence = {
  evidenceId: string;
  entryId: string;
  evidenceType: string;
  evidenceRef: string;
  createdAt: string;
};

export type WorkflowRun = {
  runId: string;
  checkpointId: string;
  version: number;
  status: string;
};

export type QuestionField = {
  name: string;
  label: string;
  type: "TEXT" | "SINGLE_SELECT" | "CONFIRM";
  required: boolean;
  options: string[];
  suggestion?: { value: string; source: string; memoryEntryId: string } | null;
};

export type WorkflowQuestion = {
  questionId: string;
  checkpointId: string;
  cardType: string;
  title: string;
  prompt: string;
  fields: QuestionField[];
};

export type WorkflowQuestionEvent = { question: WorkflowQuestion; workflowRun: WorkflowRun };

export type Intervention = {
  replyId: string;
  message: string;
  tools: Array<{ toolCallId: string; toolName: string; arguments: Record<string, string> }>;
};

export type TimelineEvent = {
  id: string;
  type: string;
  data: unknown;
  at: string;
};

export type AgentError = { code?: string; message?: string };

export type RefundCommand = {
  refundId: string;
  workflowRunId: string;
  status: "PENDING" | "PROCESSING" | "RETRY_WAIT" | "COMPLETED" | "FAILED";
  amount: number;
  currency: string;
  retryId: string;
  attemptCount: number;
  nextAttemptAt: string;
  leaseUntil: string | null;
  failureCode: string;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type AfterSalesCase = {
  caseId: string;
  workflowRunId: string;
  userId: string;
  orderId: string;
  reason: string;
  description: string;
  handlingMode: "AUTO_REFUND" | "MANUAL_REVIEW";
  status: "PENDING_REVIEW" | "REFUND_PROCESSING" | "COMPLETED" | "REFUND_FAILED" | "REJECTED";
  amount: number | null;
  currency: string;
  refundId: string;
  operatorId: string;
  decisionId: string;
  decisionNote: string;
  reviewedAt: string | null;
  failureCode: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  refundCommand: RefundCommand | null;
};

export type AfterSalesCasePage = {
  items: AfterSalesCase[];
  page: number;
  size: number;
  hasNext: boolean;
};
