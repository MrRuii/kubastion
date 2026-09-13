import { Component, input } from '@angular/core';
import { PodView } from './models';

/**
 * Tabella dei pod. Nessuna logica di rete qui dentro: riceve la lista e la
 * disegna. I dati arrivano dal polling che gira nel backend.
 */
@Component({
  selector: 'kb-pods',
  standalone: true,
  template: `
    @if (pods().length === 0) {
      <p class="empty">Nessun pod nel namespace.</p>
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
          </tr>
        </thead>
        <tbody>
          @for (pod of pods(); track pod.name) {
            <tr>
              <td class="name" [class.bad-text]="!pod.healthy">{{ pod.name }}</td>
              <td><span class="badge" [class.bad]="!pod.healthy">{{ pod.status }}</span></td>
              <td>{{ pod.ready }}</td>
              <td class="num" [class.hot]="pod.restarts > 0">{{ pod.restarts }}</td>
              <td class="muted">{{ age(pod.startedAt) }}</td>
              <td class="muted">{{ pod.node }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: [`
    :host { display: block; overflow: auto; height: 100%; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 6px 12px; text-align: left; border-bottom: 1px solid var(--border); }
    th {
      color: var(--muted); font-weight: 500; font-size: 11px;
      text-transform: uppercase; letter-spacing: .6px;
      position: sticky; top: 0; background: var(--surface);
    }
    tbody tr:hover { background: var(--surface-2); }
    .num { text-align: right; }
    .hot { color: var(--warn); }
    .muted { color: var(--muted); }
    .bad-text { color: var(--bad); }
    .badge {
      display: inline-block; padding: 2px 8px; border-radius: 100px; font-size: 11.5px;
      background: rgba(62, 193, 140, .12); color: var(--ok);
    }
    .badge.bad { background: rgba(229, 83, 75, .12); color: var(--bad); }
    .empty { padding: 20px 16px; color: var(--muted); }
  `],
})
export class PodsComponent {
  readonly pods = input.required<PodView[]>();

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
