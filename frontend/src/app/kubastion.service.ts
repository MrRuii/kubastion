import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, NgZone, inject, signal } from '@angular/core';
import { CommandInfo, PodsSnapshot, RunRequest, RunResult } from './models';

/**
 * The only point of contact with the local backend.
 *
 * Two separate channels: one for pod state (JSON), one for the terminal bytes.
 * They stay apart because their volume and their purpose are nothing alike.
 */
@Injectable({ providedIn: 'root' })
export class KubastionService {
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);

  readonly snapshot = signal<PodsSnapshot | null>(null);
  readonly backendOnline = signal(false);
  readonly commands = signal<CommandInfo[]>([]);

  private socket?: WebSocket;
  private retryMs = 1000;

  connect(): void {
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) {
      return;
    }
    const socket = new WebSocket(`${wsScheme()}://${location.host}/ws/pods`);
    this.socket = socket;

    socket.onopen = () => this.zone.run(() => {
      this.backendOnline.set(true);
      this.retryMs = 1000;
    });

    socket.onmessage = (event) => this.zone.run(() => {
      try {
        this.snapshot.set(JSON.parse(event.data as string) as PodsSnapshot);
      } catch {
        // malformed message: the next snapshot puts it right
      }
    });

    socket.onclose = () => this.zone.run(() => {
      this.backendOnline.set(false);
      setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 10_000);
    });

    socket.onerror = () => socket.close();
  }

  startMonitoring(): void {
    this.http.post<PodsSnapshot>('/api/monitor/start', {}).subscribe({
      next: (snap) => this.snapshot.set(snap),
      error: () => undefined,
    });
  }

  stopMonitoring(): void {
    this.http.post<PodsSnapshot>('/api/monitor/stop', {}).subscribe({
      next: (snap) => this.snapshot.set(snap),
      error: () => undefined,
    });
  }

  /** The fixed list of commands the backend will run. Loaded once. */
  loadCommands(): void {
    if (this.commands().length > 0) {
      return;
    }
    this.http.get<CommandInfo[]>('/api/commands').subscribe({
      next: (list) => this.commands.set(list),
      error: () => undefined,
    });
  }

  runCommand(request: RunRequest): Promise<RunResult> {
    return new Promise((resolve, reject) => {
      this.http.post<RunResult>('/api/commands/run', request).subscribe({
        next: resolve,
        // The backend answers a refusal in plain words — "you have a command
        // half-typed" — so show that rather than an HTTP status.
        error: (error: HttpErrorResponse) => reject(new Error(
          typeof error.error === 'string' && error.error
            ? error.error
            : 'The command could not be run.')),
      });
    });
  }
}

export function wsScheme(): string {
  return location.protocol === 'https:' ? 'wss' : 'ws';
}
