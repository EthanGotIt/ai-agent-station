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
  | "FAILED";

export type ExternalActionStatus =
  | "PENDING"
  | "PROCESSING"
  | "RETRY_WAIT"
  | "MANUAL_RETRY_REQUIRED"
  | "SUCCEEDED";

export type AgentItemType =
  | "USER_MESSAGE"
  | "TURN_STATE"
  | "ASSISTANT_MESSAGE"
  | "TOOL_CALL"
  | "TOOL_RESULT"
  | "WORKFLOW_STARTED"
  | "WORKFLOW_QUESTION"
  | "WORKFLOW_ANSWER"
  | "WORKFLOW_RESULT"
  | "EXTERNAL_ACTION_STATUS"
  | "EXECUTION_EVENT"
  | "ERROR"
  | string;

export type AgentItemPayload =
  | { schemaVersion: 1; kind: "USER_MESSAGE"; data: string }
  | { schemaVersion: 1; kind: "ASSISTANT_MESSAGE"; data: string }
  | { schemaVersion: 1; kind: "TURN_STATE"; data: { status: AgentTurnStatus; errorCode?: string | null } }
  | { schemaVersion: 1; kind: "WORKFLOW_QUESTION"; data: QuestionCardState }
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
  options?: string[];
};

export type QuestionCardState = {
  runId: string;
  questionId: string;
  checkpointId: string;
  version: number;
  title: string;
  prompt: string;
  fields: QuestionField[];
};

export type ThreadViewTurn = {
  turnId: string;
  userMessage: string;
  content: string;
  status: AgentTurnStatus;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
  workflowRunId: string | null;
  externalActionStatus: ExternalActionStatus | null;
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
