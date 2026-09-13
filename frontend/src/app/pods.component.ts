import { Component, inject, signal } from '@angular/core';
import { KubastionService } from './kubastion.service';
import { PodView } from './models';

/**
 * La tabella dei pod e il pannello dei log.
 *
 * Nessun timer qui dentro: i dati arrivano spinti dal backend, che a sua volta
 * li riceve spinti da Kubernetes. Quello che vedi e' vecchio di millisecondi,
 * non di un intervallo di polling.
 */
@Component({
  selector: 'kb-pods',
  standalone: true,
  template: `
    @if (snapshot(); as snap) {
      @if (snap.pods.length === 0) {
        <p class="empty">Nessun pod nel namespace <b>{{ snap.namespace }}</b>.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Pod</th>
              <th>Stato</th>
              <th>Ready</th>
              <th class="num">Restart</th>
              <th>Eta</th>
              <th>Nodo</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (pod of snap.pods; track pod.name) {
              <tr [class.unhealthy]="!pod.healthy">
                <td class="name">{{ pod.name }}</td>
                <td><span class="badge" [class.bad]="!pod.healthy">{{ pod.status }}</span></td>
                <td>{{ pod.ready }}</td>
                <td class="num" [class.hot]="pod.restarts > 0">{{ pod.restarts }}</td>
                <td class="muted">{{ age(pod.startedAt) }}</td>
                <td class="muted">{{ pod.node }}</td>
                <td class="actions">
                  <button type="button" (click)="openLogs(pod)">log</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    } @else {
      <p class="empty">In attesa del primo aggiornamento…</p>
    }

    @if (logPod(); as pod) {
      <div class="logs">
        <div class="logs-head">
          <b>{{ pod }}</b>
          <span class="spacer"></span>
          <button type="button" (click)="reloadLogs()" [disabled]="logLoading()">aggiorna</button>
          <button type="button" (click)="closeLogs()">chiudi</button>
        </div>
        @if (logLoading()) {
          <pre class="muted">caricamento…</pre>
        } @else if (logError()) {
          <pre class="error">{{ logError() }}</pre>
        } @else {
          <pre>{{ logText() }}</pre>
        }
      </div>
    }
  `,
  styles: [`
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 7px 12px; text-align: left; border-bottom: 1px solid var(--border); }
    th { color: var(--muted); font-weight: 500; font-size: 11.5px; text-transform: uppercase; letter-spacing: .6px; }
    tbody tr:hover { background: var(--surface); }
    .name { color: var(--text); }
    .num { text-align: right; }
    .hot { color: var(--warn); }
    .muted { color: var(--muted); }
    .actions { text-align: right; width: 1%; white-space: nowrap; }
    .badge {
      display: inline-block; padding: 2px 8px; border-radius: 100px; font-size: 11.5px;
      background: rgba(62, 193, 140, .12); color: var(--ok);
    }
    .badge.bad { background: rgba(229, 83, 75, .12); color: var(--bad); }
    .unhealthy .name { color: var(--bad); }
    .empty { padding: 28px 16px; color: var(--muted); }

    .logs {
      position: fixed; left: 0; right: 0; bottom: 0; height: 55vh;
      background: var(--surface); border-top: 1px solid var(--border);
      display: flex; flex-direction: column;
      box-shadow: 0 -12px 30px rgba(0, 0, 0, .4);
    }
    .logs-head {
      display: flex; align-items: center; gap: 8px;
      padding: 9px 14px; border-bottom: 1px solid var(--border);
    }
    .spacer { flex: 1; }
    pre {
      margin: 0; padding: 12px 14px; overflow: auto; flex: 1;
      font-size: 12.5px; line-height: 1.5; white-space: pre-wrap; word-break: break-word;
    }
    pre.error { color: var(--bad); }
  `],
})
export class PodsComponent {
  private readonly api = inject(KubastionService);

  readonly snapshot = this.api.snapshot;
  readonly logPod = signal<string | null>(null);
  readonly logText = signal('');
  readonly logLoading = signal(false);
  readonly logError = signal<string | null>(null);

  openLogs(pod: PodView): void {
    this.fetchLogs(pod.name);
  }

  /** Ricarica i log del pod gia' aperto nel pannello. */
  reloadLogs(): void {
    const pod = this.logPod();
    if (pod) {
      this.fetchLogs(pod);
    }
  }

  private fetchLogs(name: string): void {
    this.logPod.set(name);
    this.logLoading.set(true);
    this.logError.set(null);
    this.api.logs(name).subscribe({
      next: (text) => {
        this.logText.set(text);
        this.logLoading.set(false);
      },
      error: (err: { error?: string }) => {
        this.logError.set(err?.error ?? 'Impossibile recuperare i log.');
        this.logLoading.set(false);
      },
    });
  }

  closeLogs(): void {
    this.logPod.set(null);
    this.logText.set('');
    this.logError.set(null);
  }

  /** Eta in forma compatta, come la mostra kubectl: 3d, 5h, 12m. */
  age(startedAt: string): string {
    if (!startedAt) {
      return '';
    }
    const started = Date.parse(startedAt);
    if (Number.isNaN(started)) {
      return '';
    }
    const seconds = Math.max(0, Math.floor((Date.now() - started) / 1000));
    const days = Math.floor(seconds / 86400);
    if (days > 0) { return `${days}d`; }
    const hours = Math.floor(seconds / 3600);
    if (hours > 0) { return `${hours}h`; }
    const minutes = Math.floor(seconds / 60);
    if (minutes > 0) { return `${minutes}m`; }
    return `${seconds}s`;
  }
}
