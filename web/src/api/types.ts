/** What the hub's admin API returns (src/main/java/.../admin). Metadata only: no message content. */

export type RouteStatus = 'ok' | 'idle' | 'degraded' | 'down';

export interface Me {
  subject: string;
  canReplay: boolean;
  hl7Enabled: boolean;
}

export interface RouteHealth {
  route: string;
  status: RouteStatus;
  calls15m: number;
  failures15m: number;
  calls24h: number;
  failures24h: number;
  p95Ms: number | null;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
}

export interface Queues {
  hl7InboundPending: number;
  hl7InboundFailed: number;
  hl7InboundRejected: number;
  hl7OutboundPending: number;
  hl7OutboundFailed: number;
  eventsWaiting: number;
}

export interface Overview {
  generatedAt: string;
  routes: RouteHealth[];
  queues: Queues;
  openAlerts: number;
}

export interface Alert {
  id: string;
  kind: string;
  subject: string;
  openedAt: string;
  resolvedAt: string | null;
  detail: string;
  notified: boolean;
}

export interface Letter {
  id: string;
  queue: 'inbound' | 'outbound';
  status: string;
  sender: string | null;
  controlId: string | null;
  receivedAt: string;
  failedAt: string | null;
  attempts: number;
  lastStatus: number | null;
  lastError: string | null;
  replayable: boolean;
  replayCount: number;
  lastReplayedAt: string | null;
  lastReplayedBy: string | null;
}

export interface Identifier {
  system: string;
  value: string;
}

export interface Message {
  id: string;
  receivedAt: string;
  route: string;
  senderClientId: string | null;
  clinicSlug: string | null;
  method: string;
  path: string;
  contentType: string | null;
  requestSize: number | null;
  requestSha256: string | null;
  bundleId: string | null;
  resourceSummary: unknown;
  upstreamStatus: number | null;
  responseStatus: number | null;
  latencyMs: number | null;
  error: string | null;
  identifiers: Identifier[];
}

export interface Step {
  at: string;
  kind: string;
  text: string;
}

export interface Related {
  id: string;
  receivedAt: string;
  route: string;
  method: string;
  responseStatus: number | null;
}

export interface Timeline {
  id: string;
  steps: Step[];
  related: Related[];
}
