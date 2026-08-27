export type ThreadStatus = "ACTIVE" | "ARCHIVED";

export type AgentThread = {
  threadId: string;
  title: string;
  status: ThreadStatus;
  contextType: string | null;
  contextId: string | null;
  nextSequence: number;
  createdAt: string;
  updatedAt: string;
};

export type AgentTurnStatus =
  | "QUEUED"
  | "ACTIVE"
  | "WAITING_USER_INPUT"
  | "WAITING_EXTERNAL_ACTION"
  | "COMPLETED"
  | "CANCELLED"
  | "TIMED_OUT"
  | "FAILED"
  | "MANUAL_RETRY_REQUIRED";

export type ExternalActionStatus =
  | "PENDING"
  | "PROCESSING"
  | "RETRY_WAIT"
  | "MANUAL_RETRY_REQUIRED"
  | "SUCCEEDED";

export type ExternalActionReceipt = {
  actionType?: string;
  orderId?: string;
  code?: string;
  message?: string;
  attemptCount?: number;
  retryCycleAttemptCount?: number;
  maxAttempts?: number;
  nextAttemptAt?: string;
  verificationStatus?: string;
  verificationMessage?: string;
  verifiedAt?: string;
};

export type AgentItemType =
  | "USER_MESSAGE"
  | "TURN_STATE"
  | "ASSISTANT_MESSAGE"
  | "TOOL_CALL"
  | "TOOL_RESULT"
  | "WORKFLOW_STARTED"
  | "QUESTION_CARD"
  | "QUESTION_ANSWER"
  | "WORKFLOW_CHECKPOINT"
  | "WORKFLOW_DECISION"
  | "WORKFLOW_QUESTION"
  | "WORKFLOW_ANSWER"
  | "WORKFLOW_RESULT"
  | "EXTERNAL_ACTION_STATUS"
  | "ORDER_ACTION_REQUEST"
  | "WORKFLOW_STEP"
  | "AGENT_CONTINUATION"
  | "AGENT_DECISION"
  | "EXECUTION_EVENT"
  | "ERROR"
  | string;

export type AgentItemPayload =
  | { schemaVersion: 1; kind: "USER_MESSAGE"; data: string }
  | { schemaVersion: 1; kind: "ASSISTANT_MESSAGE"; data: string }
  | { schemaVersion: 1; kind: "TURN_STATE"; data: { status: AgentTurnStatus; errorCode?: string | null } }
  | { schemaVersion: 1; kind: "QUESTION_CARD"; data: QuestionCardState }
  | { schemaVersion: 1; kind: "QUESTION_ANSWER"; data: QuestionAnswerFact }
  | { schemaVersion: 1; kind: "WORKFLOW_CHECKPOINT"; data: WorkflowCheckpointState }
  | { schemaVersion: 1; kind: "WORKFLOW_DECISION"; data: WorkflowDecisionFact }
  | { schemaVersion: 1; kind: "WORKFLOW_QUESTION"; data: QuestionCardState }
  | { schemaVersion: 1; kind: "ORDER_ACTION_REQUEST"; data: { sourceTurnId: string; orderId: string; actionType: OrderActionType } }
  | { schemaVersion: 1; kind: "WORKFLOW_STEP"; data: WorkflowStepFact }
  | { schemaVersion: 1; kind: "AGENT_CONTINUATION"; data: AgentContinuationFact }
  | { schemaVersion: 1; kind: "AGENT_DECISION"; data: AgentDecisionFact }
  | { schemaVersion: 1; kind: AgentItemType; data: unknown };

export type AgentItemWire = {
  itemId: string;
  turnId: string | null;
  sequence: number;
  type: AgentItemType;
  schemaVersion: number;
  payload: string;
  createdAt: string;
};

export type AgentItem = {
  itemId: string;
  turnId: string | null;
  sequence: number;
  type: AgentItemType;
  schemaVersion: 1;
  payload: AgentItemPayload;
  createdAt: string;
};

export type AgentThreadEvent = {
  eventId: string;
  threadId: string;
  turnId: string | null;
  itemId: string | null;
  type: string;
  payload: string;
  sequence: number;
  timestamp: string;
};

export type QuestionField = {
  name: string;
  label: string;
  type: string;
  required: boolean;
  maxLength?: number;
  options?: string[];
  allowCustom?: boolean;
};

export type QuestionSummaryLine = {
  label: string;
  value: string;
};

export type QuestionCardState = {
  kind?: "QUESTION_CARD" | "LEGACY_WORKFLOW_QUESTION";
  runId: string | null;
  questionId: string;
  turnId?: string | null;
  resumeTarget: "AGENT" | "WORKFLOW";
  operation?: string;
  step?: string;
  stepNo?: number;
  version: number;
  title: string;
  prompt: string;
  fields: QuestionField[];
  summary?: QuestionSummaryLine[];
  submitLabel?: string;
  cancelLabel?: string;
  /** 只允许历史 Item 走展示兼容，不开放旧授权提交协议。 */
  legacy?: boolean;
};

export type WorkflowCheckpointState = {
  kind?: "WORKFLOW_CHECKPOINT";
  checkpointId: string;
  runId: string;
  turnId?: string | null;
  status: "OPEN" | "APPROVED" | "REJECTED" | "SUPERSEDED" | string;
  version: number;
  nodeId: string;
  actionType: string;
  orderId: string;
  impactSummary: string;
  factsFingerprint: string;
  decision?: "APPROVE" | "REJECT" | string | null;
};

export type QuestionAnswerFact = {
  questionId: string;
  runId?: string | null;
  resumeTarget?: "AGENT" | "WORKFLOW" | string | null;
  action?: "SUBMIT" | "CANCEL" | string | null;
};

export type WorkflowDecisionFact = {
  runId: string;
  checkpointId: string;
  expectedVersion?: number;
  decision: "APPROVE" | "REJECT" | string;
  factsFingerprint?: string | null;
};

export type AgentInteraction =
  | { type: "QUESTION_CARD"; question: QuestionCardState }
  | { type: "WORKFLOW_CHECKPOINT"; checkpoint: WorkflowCheckpointState };

export type QuestionAnswerAction = "SUBMIT" | "CANCEL";

export type OrderActionType =
  | "QUERY_LOGISTICS"
  | "REFRESH_ORDER"
  | "REFUND"
  | "EXPEDITE"
  | "HIDE_ORDER"
  | "RESTORE_ORDER";

export type BusinessProgressStatus = "ACTIVE" | "WAITING" | "DONE" | "ERROR";

export type BusinessProgress = {
  id: string;
  label: string;
  detail: string | null;
  status: BusinessProgressStatus;
  sequence: number;
};

export type WorkflowStepFact = {
  runId?: string;
  node: string;
  status: string;
  branch?: string | null;
  code?: string | null;
  elapsedMillis?: number;
};

export type AgentContinuationFact = {
  rootTurnId: string;
  parentTurnId: string;
  triggerRunId: string;
  triggerCommandId?: string | null;
  triggerStatus: string;
  triggerSequence: number;
  cycleNo: number;
};

export type AgentDecisionFact = {
  decision: "FINISH" | "START_WORKFLOW" | "WAIT_USER" | "STOP_LIMIT" | "FALLBACK" | string;
  cycleNo?: number;
  runId?: string | null;
  code?: string | null;
};

export type OrderCard = {
  orderId: string;
  status: string;
  createdAt: string | null;
  expectedDeliveryAt: string | null;
  lastLogisticsAt: string | null;
  logisticsStatus: string | null;
  paidAmount: number | null;
  currency: string | null;
  itemSummary: string | null;
  visibility: "ACTIVE" | "HIDDEN" | string;
};

export type LogisticsEvent = {
  eventId: string;
  status: string;
  location: string;
  description: string;
  occurredAt: string;
};

export type LogisticsTimeline = {
  orderId: string;
  events: LogisticsEvent[];
};

export type ThreadViewTurn = {
  turnId: string;
  userMessage: string;
  content: string;
  status: AgentTurnStatus;
  error: string | null;
  /** 外部业务成功后，后续 Agent 续接失败只作为非阻断提示。 */
  continuationWarning: string | null;
  startedAt: string;
  finishedAt: string | null;
  workflowRunId: string | null;
  externalActionStatus: ExternalActionStatus | null;
  externalActionReceipt: ExternalActionReceipt | null;
  items: AgentItem[];
  activities: BusinessProgress[];
  orderCards: OrderCard[];
  logisticsTimelines: LogisticsTimeline[];
  question: QuestionCardState | null;
  /** 历史旧问题只读展示，不再作为可提交的开放交互。 */
  legacyQuestion: QuestionCardState | null;
  workflowCheckpoint: WorkflowCheckpointState | null;
  sourceTurnId: string | null;
  inputKind: "MESSAGE" | "QUESTION_ANSWER" | "WORKFLOW_ANSWER" | "WORKFLOW_DECISION" | "ORDER_ACTION" | "AGENT_CONTINUATION";
  workflowSteps: WorkflowStepFact[];
  decisions: AgentDecisionFact[];
  continuation: AgentContinuationFact | null;
};

export type AgentThreadInteractionDto = {
  type: "QUESTION_CARD" | "WORKFLOW_CHECKPOINT";
  interactionId: string;
  threadId: string;
  runId: string | null;
  turnId: string | null;
  status: string;
  version: number;
  resumeTarget: string | null;
  title: string | null;
  prompt: string | null;
  fieldsJson: string | null;
  nodeId: string | null;
  actionType: string | null;
  orderId: string | null;
  impactSummary: string | null;
  factsFingerprint: string | null;
  decision: string | null;
  legacy?: boolean;
};

export type AgentThreadPage = {
  items: AgentThread[];
  page: number;
  size: number;
  total: number;
};

export type AgentItemPage = {
  items: AgentItemWire[];
  afterSequence: number;
  nextAfterSequence: number;
  hasMore: boolean;
};
