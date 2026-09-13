/** Mirrors the backend records: PodsSnapshot / PodView / the command catalogue. */

export type MonitorState = 'IDLE' | 'MONITORING' | 'PAUSED' | 'ERROR';

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

/** One entry of the fixed list of commands kubastion is willing to run. */
export interface CommandInfo {
  id: string;
  label: string;
  scope: 'POD' | 'NAMESPACE' | 'CLUSTER';
  description: string;
  needsPod: boolean;
  tailable: boolean;
  /** Used often enough to get a button of its own instead of a menu entry. */
  quick: boolean;
}

export interface RunResult {
  id: string;
  title: string;
  /** The kubectl line that actually ran, shown so nothing is hidden from you. */
  command: string;
  output: string;
  ranAt: number;
}

/** What the UI asks for; the backend never accepts a command line. */
export interface RunRequest {
  id: string;
  pod?: string;
  tailLines?: number;
}
