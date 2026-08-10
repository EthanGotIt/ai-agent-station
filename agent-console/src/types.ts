export type MemoryOptions = { generate?: boolean; use?: boolean };

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
