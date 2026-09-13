import { Component, OnDestroy, computed, input, signal } from '@angular/core';
import { PodView } from './models';

/**
 * The pod table. No networking in here: it receives the list and draws it.
 * The data comes from the polling loop running in the backend.
 */
@Component({
  selector: 'kb-pods',
  standalone: true,
  template: `
    <div class="toolbar">
      <div class="search">
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" stroke-width="1.5" />
          <path d="M10.5 10.5 L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
        <input type="search" placeholder="Filter pods…" spellcheck="false"
               [value]="filter()" (input)="onFilter($event)" />
        @if (filter()) {
          <button type="button" class="icon" title="Clear filter" (click)="filter.set('')">×</button>
        }
      </div>

      <button type="button" class="chip" [class.active]="!onlyIssues()"
              (click)="onlyIssues.set(false)">
        {{ pods().length }} {{ pods().length === 1 ? 'pod' : 'pods' }}
      </button>

      @if (issues() > 0) {
        <button type="button" class="chip issues" [class.active]="onlyIssues()"
                (click)="onlyIssues.set(!onlyIssues())"
                title="Show only pods that need attention">
          {{ issues() }} needing attention
        </button>
      } @else {
        <span class="chip ok-chip">all healthy</span>
      }

      <span class="spacer"></span>
      <span class="updated" [class.stale]="stale()" title="Time since the last successful poll">
        updated {{ updatedAgo() }}
      </span>
    </div>

    @if (visible().length === 0) {
      <p class="empty">
        @if (pods().length === 0) {
          No pods in this namespace.
        } @else {
          No pod matches the current filter.
          <button type="button" class="link" (click)="resetFilters()">Show all</button>
        }
      </p>
    } @else {
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>Pod</th>
              <th>Status</th>
              <th>Ready</th>
              <th class="num">Restarts</th>
              <th class="num">Age</th>
              <th>Node</th>
            </tr>
          </thead>
          <tbody>
            @for (pod of visible(); track pod.name) {
              <tr [class.unhealthy]="!pod.healthy">
                <td class="name">
                  <span class="rail" [class.bad]="!pod.healthy"></span>
                  <span class="label" [title]="pod.name">{{ pod.name }}</span>
                  <button type="button" class="copy" (click)="copy(pod.name)"
                          [title]="'Copy ' + pod.name">
                    {{ copied() === pod.name ? 'copied' : 'copy' }}
                  </button>
                </td>
                <td><span class="badge" [class.bad]="!pod.healthy">{{ pod.status }}</span></td>
                <td class="ready" [class.partial]="isPartial(pod)">{{ pod.ready }}</td>
                <td class="num" [class.hot]="pod.restarts > 0">{{ pod.restarts }}</td>
                <td class="num muted">{{ age(pod.startedAt) }}</td>
                <td class="muted node">{{ pod.node || '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }

    .toolbar {
      display: flex; align-items: center; gap: 8px; flex: none;
      padding: 8px 12px;
      border-bottom: 1px solid var(--border);
      background: var(--surface);
    }
    .spacer { flex: 1; }

    .search { position: relative; display: flex; align-items: center; }
    .search svg {
      position: absolute; left: 8px; width: 13px; height: 13px;
      color: var(--muted); pointer-events: none;
    }
    .search input {
      font: inherit; font-size: 12.5px;
      width: 210px; padding: 5px 26px 5px 26px;
      color: var(--text); background: var(--bg);
      border: 1px solid var(--border); border-radius: var(--radius);
      outline: none;
    }
    .search input::placeholder { color: var(--muted); }
    .search input:focus { border-color: var(--accent); }
    .search input::-webkit-search-cancel-button { display: none; }
    .icon {
      position: absolute; right: 4px;
      padding: 0 5px; line-height: 1; font-size: 15px;
      background: none; border: none; color: var(--muted);
    }
    .icon:hover { color: var(--text); }

    .chip {
      font: inherit; font-size: 11.5px; white-space: nowrap;
      padding: 4px 10px; border-radius: 100px;
      border: 1px solid var(--border); background: var(--surface-2); color: var(--muted);
    }
    button.chip { cursor: pointer; }
    button.chip:hover { color: var(--text); border-color: var(--muted); }
    .chip.active { color: var(--text); border-color: var(--accent); }
    .chip.issues { color: var(--bad); border-color: rgba(229, 83, 75, .35); }
    .chip.issues.active { background: rgba(229, 83, 75, .14); border-color: var(--bad); }
    .chip.ok-chip { color: var(--ok); border-color: rgba(62, 193, 140, .3); }

    .updated { font-size: 11.5px; color: var(--muted); white-space: nowrap; }
    .updated.stale { color: var(--warn); }

    .scroll { flex: 1; min-height: 0; overflow: auto; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 7px 12px; text-align: left; border-bottom: 1px solid var(--border); }
    th {
      color: var(--muted); font-weight: 500; font-size: 11px;
      text-transform: uppercase; letter-spacing: .6px;
      position: sticky; top: 0; z-index: 1;
      background: var(--surface);
      box-shadow: inset 0 -1px 0 var(--border);
      border-bottom: none;
    }
    tbody tr:hover { background: var(--surface-2); }
    tbody tr.unhealthy { background: rgba(229, 83, 75, .05); }
    tbody tr.unhealthy:hover { background: rgba(229, 83, 75, .1); }

    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .muted { color: var(--muted); }
    .hot { color: var(--warn); }
    .node { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .name { display: flex; align-items: center; gap: 9px; }
    .rail { width: 3px; height: 15px; border-radius: 2px; background: var(--ok); flex: none; }
    .rail.bad { background: var(--bad); }
    .label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .copy {
      margin-left: auto; flex: none;
      font: inherit; font-size: 10.5px; letter-spacing: .4px;
      padding: 2px 7px; border-radius: var(--radius);
      border: 1px solid var(--border); background: var(--surface-2); color: var(--muted);
      opacity: 0; transition: opacity .12s ease;
    }
    tr:hover .copy, .copy:focus-visible { opacity: 1; }
    .copy:hover { color: var(--accent); border-color: var(--accent); }

    .ready.partial { color: var(--warn); }
    .badge {
      display: inline-block; padding: 2px 9px; border-radius: 100px; font-size: 11.5px;
      background: rgba(62, 193, 140, .12); color: var(--ok);
    }
    .badge.bad { background: rgba(229, 83, 75, .12); color: var(--bad); }

    .empty { padding: 24px 16px; color: var(--muted); }
    .link {
      font: inherit; background: none; border: none; padding: 0 0 0 4px;
      color: var(--accent); text-decoration: underline; cursor: pointer;
    }
  `],
})
export class PodsComponent implements OnDestroy {
  readonly pods = input.required<PodView[]>();
  /** Epoch millis of the last successful poll, straight from the snapshot. */
  readonly updatedAt = input<number>(0);

  readonly filter = signal('');
  readonly onlyIssues = signal(false);
  readonly copied = signal('');

  /** Ticks once a second so ages and "updated ago" stay honest between polls. */
  private readonly now = signal(Date.now());
  private readonly clock = setInterval(() => this.now.set(Date.now()), 1000);
  private copyTimer?: ReturnType<typeof setTimeout>;

  ngOnDestroy(): void {
    clearInterval(this.clock);
    clearTimeout(this.copyTimer);
  }

  readonly issues = computed(() => this.pods().filter((pod) => !pod.healthy).length);

  readonly visible = computed(() => {
    const query = this.filter().trim().toLowerCase();
    return this.pods()
      .filter((pod) => !this.onlyIssues() || !pod.healthy)
      .filter((pod) => !query
        || pod.name.toLowerCase().includes(query)
        || pod.status.toLowerCase().includes(query));
  });

  readonly updatedAgo = computed(() => {
    const at = this.updatedAt();
    if (!at) {
      return 'never';
    }
    const seconds = Math.max(0, Math.round((this.now() - at) / 1000));
    if (seconds < 2) { return 'just now'; }
    if (seconds < 60) { return `${seconds}s ago`; }
    return `${Math.floor(seconds / 60)}m ago`;
  });

  /** Past a few polling cycles the table is no longer telling you the truth. */
  readonly stale = computed(() => {
    const at = this.updatedAt();
    return at > 0 && this.now() - at > 15_000;
  });

  onFilter(event: Event): void {
    this.filter.set((event.target as HTMLInputElement).value);
  }

  resetFilters(): void {
    this.filter.set('');
    this.onlyIssues.set(false);
  }

  isPartial(pod: PodView): boolean {
    const [ready, total] = pod.ready.split('/');
    return !!total && ready !== total;
  }

  /** The pod name is what you paste into the next command, so make it one click. */
  copy(name: string): void {
    navigator.clipboard?.writeText(name).then(() => {
      this.copied.set(name);
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => this.copied.set(''), 1200);
    }, () => undefined);
  }

  /** Age in the compact form kubectl uses: 3d, 5h, 12m. */
  age(startedAt: string): string {
    if (!startedAt) {
      return '—';
    }
    const started = Date.parse(startedAt);
    if (Number.isNaN(started)) {
      return '—';
    }
    const seconds = Math.max(0, Math.floor((this.now() - started) / 1000));
    const days = Math.floor(seconds / 86400);
    if (days > 0) { return `${days}d`; }
    const hours = Math.floor(seconds / 3600);
    if (hours > 0) { return `${hours}h`; }
    const minutes = Math.floor(seconds / 60);
    if (minutes > 0) { return `${minutes}m`; }
    return `${seconds}s`;
  }
}
