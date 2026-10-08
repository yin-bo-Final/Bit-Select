export type OperationsPage<T> = {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
};
export type OperationsRequestStatus =
  "running" | "completed" | "failed" | "cancelled";
export type OperationsRequest = {
  id: string;
  conversationId: string;
  userId: number;
  questionPreview: string;
  status: OperationsRequestStatus;
  startedAt: string;
  finishedAt: string | null;
  lastActivityAt: string;
  firstTokenMs: number | null;
  totalMs: number | null;
  outputChars: number;
  errorCode: string | null;
  stale: boolean;
};
export type OperationsRequestStep = {
  code: string;
  label: string;
  status: OperationsRequestStatus;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
};
export type OperationsRequestDetail = OperationsRequest & {
  steps: OperationsRequestStep[];
};
export type OperationsRequestSummary = {
  total: number;
  running: number;
  completed: number;
  failed: number;
  cancelled: number;
  stale: number;
  firstRecordedAt: string | null;
  lastRecordedAt: string | null;
};
export type OperationsService = {
  id: string;
  name: string;
  layer: "application" | "middleware";
  status: "up" | "down" | "unknown";
  probe: "http" | "tcp";
  latencyMs: number | null;
  checkedAt: string;
  detailCode: string;
};
export type OperationsQueueSummary = {
  kind: "commerce" | "memory";
  name: string;
  pending: number;
  processing: number;
  retrying: number;
  completed: number;
  failed: number;
  total: number;
  oldestPendingAt: string | null;
  scope: string;
};
export type OperationsOverview = {
  sampledAt: string;
  serviceCacheSeconds: number;
  services: OperationsService[];
  commerce: {
    users: number;
    customers: number;
    orders: number;
    ordersByStatus: Record<
      | "PENDING_PAYMENT"
      | "PAID"
      | "SHIPPED"
      | "COMPLETED"
      | "CANCELLED"
      | "REFUNDED",
      number
    >;
  };
  queues: OperationsQueueSummary[];
  knowledge: { documents: number; indexed: number; chunks: number };
};
export type OperationsQueueEvent = {
  id: string;
  kind: "commerce" | "memory";
  status: "pending" | "processing" | "retrying" | "completed" | "failed";
  rawStatus: string;
  eventType: string;
  topic: string;
  aggregateId: string | null;
  attempts: number;
  createdAt: string;
  updatedAt: string | null;
  completedAt: string | null;
  errorCode: string | null;
};
