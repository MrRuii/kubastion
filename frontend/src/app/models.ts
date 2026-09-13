/** Rispecchia i record del backend: PodsSnapshot / PodView. */

export type Connection = 'CONNECTED' | 'RECONNECTING' | 'STARTING';

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
  connection: Connection;
  message: string;
  namespace: string;
  pods: PodView[];
  updatedAt: number;
}
