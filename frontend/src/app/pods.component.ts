import { Component, OnDestroy, computed, input, model, output, signal } from '@angular/core';
import { CommandInfo, PodView } from './models';

type SortKey = 'name' | 'status' | 'ready' | 'restarts' | 'age';

/**
 * The pod grid. No networking in here: it receives the list and draws it.
 * The data comes from the polling loop running in the backend.
 */
@Component({
  selector: 'kb-pods',
  standalone: true,
  template: `
    <div class="toolbar">
      <div class="search">
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" stroke-width="1.5"/>
          <path d="M10.5 10.5 L14 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        <input type="search" placeholder="Filter by name or status" spellcheck="false"
               aria-label="Filter pods" [value]="filter()" (input)="onFilter($event)"/>
        @if (filter()) {
          <button type="button" class="clear" title="Clear filter" (click)="filter.set('')">×</button>
        }
      </div>

      <label class="toggle" [class.on]="issuesOnly()">
        <input type="checkbox" [checked]="issuesOnly()" (change)="issuesOnly.set(!issuesOnly())"/>
        Only issues
      </label>

      @if (clusterCommands().length > 0) {
        <select class="picker" aria-label="Run a command"
                [value]="''" (change)="onPick($event)">
          <option value="">Run a command…</option>
          @for (command of clusterCommands(); track command.id) {
            <option [value]="command.id" [title]="command.description">{{ command.label }}</option>
          }
        </select>
      }

      <span class="spacer"></span>

      <span class="result">
        {{ visible().length }} of {{ pods().length }}
        @if (sortKey() !== 'name' || sortDir() !== 1) {
          <button type="button" class="link" (click)="resetSort()">reset sort</button>
        }
      </span>
    </div>

    @if (visible().length === 0) {
      <div class="empty">
        @if (pods().length === 0) {
          <p>No pods in this namespace.</p>
        } @else {
          <p>No pod matches the current filter.</p>
          <button type="button" (click)="resetFilters()">Clear filters</button>
        }
      </div>
    } @else {
      <div class="grid">
        <table>
          <thead>
            <tr>
              <th class="c-name" [class.sorted]="sortKey() === 'name'">
                <button type="button" (click)="sortBy('name')">Pod{{ arrow('name') }}</button>
              </th>
              <th class="c-status" [class.sorted]="sortKey() === 'status'">
                <button type="button" (click)="sortBy('status')">Status{{ arrow('status') }}</button>
              </th>
              <th class="c-ready num" [class.sorted]="sortKey() === 'ready'">
                <button type="button" (click)="sortBy('ready')">Ready{{ arrow('ready') }}</button>
              </th>
              <th class="c-restarts num" [class.sorted]="sortKey() === 'restarts'">
                <button type="button" (click)="sortBy('restarts')">Restarts{{ arrow('restarts') }}</button>
              </th>
              <th class="c-age num" [class.sorted]="sortKey() === 'age'">
                <button type="button" (click)="sortBy('age')">Age{{ arrow('age') }}</button>
              </th>
              <th class="c-node">Node</th>
            </tr>
          </thead>
          <tbody>
            @for (pod of visible(); track pod.name) {
              <tr [class.flagged]="!pod.healthy" [class.selected]="pod.name === selected()"
                  (click)="inspect.emit(pod.name)"
                  title="Logs, describe, events for this pod">
                <td class="c-name">
                  <span class="rail" [class.bad]="!pod.healthy"></span>
                  <span class="podname" [title]="pod.name">{{ pod.name }}</span>
                  <span class="acts">
                    <button type="button" class="act" [title]="'Logs for ' + pod.name"
                            (click)="$event.stopPropagation(); inspect.emit(pod.name)">logs</button>
                    <button type="button" class="copy" [title]="'Copy ' + pod.name"
                            (click)="$event.stopPropagation(); copy(pod.name)">
                      {{ copied() === pod.name ? 'copied' : 'copy' }}
                    </button>
                  </span>
                </td>
                <td class="c-status">
                  <span class="status" [class.bad]="!pod.healthy">
                    <i class="dot"></i>{{ pod.status }}
                  </span>
                </td>
                <td class="c-ready num mono" [class.warn]="isPartial(pod)">{{ pod.ready }}</td>
                <td class="c-restarts num mono" [class.warn]="pod.restarts > 0">{{ pod.restarts }}</td>
                <td class="c-age num mono faint">{{ age(pod.startedAt) }}</td>
                <td class="c-node faint">{{ pod.node || '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }

    /* ── toolbar ──────────────────────────────────────────────────────── */
    .toolbar {
      display: flex; align-items: center; gap: 8px; flex: none; flex-wrap: wrap;
      padding: 7px 10px;
      border-bottom: 1px solid var(--border);
      background: var(--panel-2);
    }
    .spacer { flex: 1; }

    .search { position: relative; display: flex; align-items: center; flex: 0 1 240px; }
    .search svg {
      position: absolute; left: 8px; width: 13px; height: 13px;
      color: var(--faint); pointer-events: none;
    }
    .search input {
      width: 100%; font-size: 12px; padding: 5px 24px 5px 26px; outline: none;
    }
    .search input::placeholder { color: var(--faint); }
    .search input:focus { border-color: var(--accent); }
    .search input::-webkit-search-cancel-button { display: none; }
    .clear {
      position: absolute; right: 3px; padding: 0 5px; line-height: 1; font-size: 15px;
      background: none; border: none; color: var(--faint);
    }
    .clear:hover { color: var(--text); background: none; }

    .toggle {
      display: inline-flex; align-items: center; gap: 6px; white-space: nowrap;
      font-size: 12px; color: var(--muted); cursor: pointer;
      padding: 4px 10px 4px 8px; border-radius: var(--radius);
      border: 1px solid var(--border-2); background: var(--panel);
    }
    .toggle input { margin: 0; accent-color: var(--accent); }
    .toggle.on { color: var(--bad); border-color: var(--bad); background: var(--bad-bg); }

    .picker { font-size: 12px; padding: 4px 6px; max-width: 170px; }
    .result { font-size: 11.5px; color: var(--faint); white-space: nowrap; }
    .link {
      font: inherit; font-size: 11.5px; background: none; border: none; padding: 0 0 0 6px;
      color: var(--accent); text-decoration: underline; cursor: pointer;
    }
    .link:hover { background: none; }

    /* ── grid ─────────────────────────────────────────────────────────── */
    .grid { flex: 1; min-height: 0; overflow: auto; }
    /* Fixed layout so the columns do not jump around as pods come and go, and
       so a 60-character pod name truncates instead of pushing Status off a
       phone screen. The name column takes whatever is left. */
    table { width: 100%; table-layout: fixed; border-collapse: separate; border-spacing: 0; }

    th {
      position: sticky; top: 0; z-index: 1;
      padding: 0; text-align: left;
      background: var(--panel-3);
      border-bottom: 1px solid var(--border-2);
      box-shadow: inset -1px 0 0 var(--border);
    }
    th button {
      width: 100%; text-align: inherit;
      font-size: 10.5px; font-weight: 600; letter-spacing: .5px; text-transform: uppercase;
      color: var(--muted); background: none; border: none; border-radius: 0;
      padding: 7px 10px; white-space: nowrap;
    }
    th.num button { text-align: right; }
    th button:hover { background: var(--border); color: var(--text); }
    th.sorted button { color: var(--accent); }
    th:not(:has(button)) {
      font-size: 10.5px; font-weight: 600; letter-spacing: .5px; text-transform: uppercase;
      color: var(--muted); padding: 7px 10px;
    }

    td {
      height: var(--row); padding: 0 10px;
      border-bottom: 1px solid var(--border);
      box-shadow: inset -1px 0 0 var(--border);
      white-space: nowrap;
    }
    tbody tr { cursor: pointer; }
    tbody tr:nth-child(even) td { background: var(--panel-2); }
    tbody tr:hover td { background: var(--accent-bg); }
    tbody tr.flagged td { background: var(--bad-bg); }
    tbody tr.flagged:hover td { background: var(--bad-bg); filter: brightness(.97); }
    tbody tr.selected td { box-shadow: inset 0 0 0 1px var(--accent); }

    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .mono { font-family: var(--mono); font-size: 12px; }
    .faint { color: var(--faint); }
    .warn { color: var(--warn); font-weight: 600; }

    /* ── cells ────────────────────────────────────────────────────────── */
    .c-name { width: auto; }
    td.c-name { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .rail { width: 3px; height: 15px; border-radius: 2px; background: var(--ok); flex: none; }
    .rail.bad { background: var(--bad); }
    .podname {
      font-family: var(--mono); font-size: 12px;
      min-width: 0; overflow: hidden; text-overflow: ellipsis;
    }
    .acts { margin-left: auto; flex: none; display: flex; gap: 4px; padding-left: 8px; }
    .act, .copy {
      font-size: 10px; letter-spacing: .3px; padding: 1px 7px;
      color: var(--muted); background: var(--panel); border-color: var(--border-2);
    }
    .act:hover, .copy:hover { color: var(--accent); border-color: var(--accent); }
    /* Logs is the button you reach for all day, so it is always there.
       Copy is a convenience and only shows when the row is under the pointer. */
    .act { color: var(--accent); border-color: var(--border-2); }
    .copy { opacity: 0; transition: opacity .12s ease; }
    tr:hover .copy, .copy:focus-visible { opacity: 1; }

    .status {
      display: inline-flex; align-items: center; gap: 6px;
      font-size: 11.5px; font-weight: 500;
      padding: 2px 9px 2px 7px; border-radius: 100px;
      background: var(--ok-bg); color: var(--ok);
    }
    .status .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
    .status.bad { background: var(--bad-bg); color: var(--bad); }
    tr.flagged .status { background: var(--panel); }

    .c-status { width: 150px; }
    .c-ready, .c-restarts, .c-age { width: 84px; }
    .c-node { width: 130px; font-size: 12px; }
    th:last-child, td:last-child { box-shadow: none; }

    .empty {
      flex: 1; display: flex; flex-direction: column;
      align-items: center; justify-content: center; gap: 10px;
      color: var(--muted); padding: 28px 16px;
    }
    .empty p { margin: 0; }

    /* ── responsive: drop the columns you can live without ────────────── */
    @media (max-width: 900px) {
      .c-node { display: none; }
    }
    @media (max-width: 700px) {
      .c-age { display: none; }
      .c-status { width: 130px; }
      .search { flex-basis: 100%; }
    }
    @media (max-width: 520px) {
      .c-ready { display: none; }
      td, th button { padding-left: 8px; padding-right: 8px; }
    }
  `],
})
export class PodsComponent implements OnDestroy {
  readonly pods = input.required<PodView[]>();
  /** Epoch millis of the last successful poll, straight from the snapshot. */
  readonly updatedAt = input<number>(0);
  /** Two-way: the header's issue counter flips this too. */
  readonly issuesOnly = model(false);
  /** The fixed catalogue; the namespace-wide entries fill the toolbar picker. */
  readonly commands = input<CommandInfo[]>([]);
  /** Pod whose output window is open, so the row it came from stays marked. */
  readonly selected = input<string | null>(null);

  /** A row was clicked: open the command window for that pod. */
  readonly inspect = output<string>();
  /** A namespace or cluster command was picked from the toolbar. */
  readonly runCommand = output<string>();

  readonly filter = signal('');
  readonly copied = signal('');
  readonly sortKey = signal<SortKey>('name');
  readonly sortDir = signal<1 | -1>(1);

  /** Ticks once a second so ages stay honest between polls. */
  private readonly now = signal(Date.now());
  private readonly clock = setInterval(() => this.now.set(Date.now()), 1000);
  private copyTimer?: ReturnType<typeof setTimeout>;

  ngOnDestroy(): void {
    clearInterval(this.clock);
    clearTimeout(this.copyTimer);
  }

  readonly visible = computed(() => {
    const query = this.filter().trim().toLowerCase();
    const rows = this.pods()
      .filter((pod) => !this.issuesOnly() || !pod.healthy)
      .filter((pod) => !query
        || pod.name.toLowerCase().includes(query)
        || pod.status.toLowerCase().includes(query));

    const key = this.sortKey();
    const direction = this.sortDir();
    return [...rows].sort((a, b) => direction * compare(a, b, key));
  });

  readonly clusterCommands = computed(() =>
    this.commands().filter((command) => !command.needsPod));

  onFilter(event: Event): void {
    this.filter.set((event.target as HTMLInputElement).value);
  }

  onPick(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const id = select.value;
    // Back to the placeholder, so picking the same command twice still runs it.
    select.value = '';
    if (id) {
      this.runCommand.emit(id);
    }
  }

  resetFilters(): void {
    this.filter.set('');
    this.issuesOnly.set(false);
  }

  resetSort(): void {
    this.sortKey.set('name');
    this.sortDir.set(1);
  }

  sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.sortDir.set(this.sortDir() === 1 ? -1 : 1);
      return;
    }
    this.sortKey.set(key);
    // Counts and ages are read "worst first"; names are read alphabetically.
    this.sortDir.set(key === 'restarts' ? -1 : 1);
  }

  arrow(key: SortKey): string {
    if (this.sortKey() !== key) {
      return '';
    }
    return this.sortDir() === 1 ? ' ↑' : ' ↓';
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
    const started = Date.parse(startedAt);
    if (!startedAt || Number.isNaN(started)) {
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

function compare(a: PodView, b: PodView, key: SortKey): number {
  switch (key) {
    case 'status':
      // Broken pods first within a status sort: that is why you are sorting.
      return (Number(a.healthy) - Number(b.healthy))
        || a.status.localeCompare(b.status)
        || a.name.localeCompare(b.name);
    case 'ready':
      return (ratio(a.ready) - ratio(b.ready)) || a.name.localeCompare(b.name);
    case 'restarts':
      return (a.restarts - b.restarts) || a.name.localeCompare(b.name);
    case 'age':
      // Ascending age means oldest first, so the timestamps sort ascending too.
      return (Date.parse(a.startedAt) || 0) - (Date.parse(b.startedAt) || 0)
        || a.name.localeCompare(b.name);
    default:
      return a.name.localeCompare(b.name);
  }
}

/** "1/2" becomes 0.5 so a partly-ready pod sorts below a fully-ready one. */
function ratio(ready: string): number {
  const [done, total] = ready.split('/').map(Number);
  if (!total || Number.isNaN(done) || Number.isNaN(total)) {
    return -1;
  }
  return done / total;
}
