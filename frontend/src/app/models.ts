/** Mirrors the backend records: PodsSnapshot / PodView. */

export type MonitorState = 'IDLE' | 'MONITORING' | 'ERROR';

export interface PodView {
  name: string;
  status: string;
  ready: string;
  restarts: number;
  node: string;
  startedAt: string;
  healthy: boolean;
}

export interface PodsSnapshot {
  state: MonitorState;
  message: string;
  namespace: string;
  pods: PodView[];
  updatedAt: number;
}
