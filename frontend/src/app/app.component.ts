import {
  Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, inject, signal,
} from '@angular/core';
import { KubastionService } from './kubastion.service';
import { CommandInfo, RunResult } from './models';
import { OutputComponent } from './output.component';
import { PodsComponent } from './pods.component';
import { TerminalComponent } from './terminal.component';

const SPLIT_KEY = 'kubastion.split';
const WIDE = '(min-width: 1180px)';

/**
 * The console shell: a terminal you drive yourself, and a grid that fills in
 * once monitoring is on.
 *
 * The order of operations is the real one: first you log in by hand in the
 * terminal (key, ssh, menu, destination), then you press "Start monitoring".
 */
@Component({
  selector: 'kb-root',
  standalone: true,
  imports: [TerminalComponent, PodsComponent, OutputComponent],
  template: `
    <header class="appbar">
      <img class="mark" src="assets/kubastion-mark.svg" alt="" width="20" height="20">
      <span class="brand">kubastion</span>

      @if (snapshot()?.namespace; as namespace) {
        <span class="field" title="Namespace being watched">
          <span class="label">namespace</span>{{ namespace }}
        </span>
      }

      <span class="spacer"></span>

      <span class="state" [class]="tone()">
        <span class="dot"></span>{{ stateLabel() }}
      </span>

      @if (monitoring()) {
        <button type="button" (click)="api.stopMonitoring()">Stop monitoring</button>
      } @else {
        <button type="button" class="primary" (click)="api.startMonitoring()"
                [disabled]="!api.backendOnline()">Start monitoring</button>
      }
    </header>

    @if (monitoring()) {
      <section class="stats">
        <div class="stat">
          <span class="k">Pods</span><span class="v">{{ total() }}</span>
        </div>
        <div class="stat">
          <span class="k">Healthy</span><span class="v ok">{{ total() - issues() }}</span>
        </div>
        <button type="button" class="stat clickable" [class.active]="issuesOnly()"
                (click)="issuesOnly.set(!issuesOnly())"
                title="Show only the pods that need attention">
          <span class="k">Needs attention</span>
          <span class="v" [class.bad]="issues() > 0">{{ issues() }}</span>
        </button>
        <div class="stat">
          <span class="k">Restarts</span>
          <span class="v" [class.warn]="restarts() > 0">{{ restarts() }}</span>
        </div>
        <div class="stat">
          <span class="k">Last update</span><span class="v small" [class.warn]="stale()">{{ updatedAgo() }}</span>
        </div>
      </section>

      <nav class="ribbon" aria-label="Cluster commands">
        <div class="group">
          <span class="group-label">Namespace</span>
          @for (command of quick('NAMESPACE'); track command.id) {
            <button type="button" class="chip" [title]="command.description"
                    [class.active]="activeCommand() === command.id && !drawerPod()"
                    (click)="openCommand(command.id)">{{ command.label }}</button>
          }
          @if (more('NAMESPACE').length > 0) {
            <details class="more" #nsMore>
              <summary class="chip">More ▾</summary>
              <div class="menu">
                @for (command of more('NAMESPACE'); track command.id) {
                  <button type="button" [title]="command.description"
                          (click)="nsMore.open = false; openCommand(command.id)">
                    <span>{{ command.label }}</span>
                    <small>{{ command.description }}</small>
                  </button>
                }
              </div>
            </details>
          }
        </div>

        <div class="group">
          <span class="group-label">Cluster</span>
          @for (command of quick('CLUSTER'); track command.id) {
            <button type="button" class="chip" [title]="command.description"
                    [class.active]="activeCommand() === command.id && !drawerPod()"
                    (click)="openCommand(command.id)">{{ command.label }}</button>
          }
          @if (more('CLUSTER').length > 0) {
            <details class="more" #clMore>
              <summary class="chip">More ▾</summary>
              <div class="menu">
                @for (command of more('CLUSTER'); track command.id) {
                  <button type="button" [title]="command.description"
                          (click)="clMore.open = false; openCommand(command.id)">
                    <span>{{ command.label }}</span>
                    <small>{{ command.description }}</small>
                  </button>
                }
              </div>
            </details>
          }
        </div>
      </nav>
    }

    @if (banner(); as note) {
      <div class="banner" [class]="note.tone">
        <span class="icon">{{ note.tone === 'bad' ? '!' : 'i' }}</span>{{ note.text }}
      </div>
    }

    @if (!monitoring() && api.backendOnline()) {
      <ol class="steps">
        <li>Load your key and connect — ssh, gateway menu, destination.</li>
        <li>Check that <code>kubectl</code> answers in that session.</li>
        <li>Press <b>Start monitoring</b>.</li>
      </ol>
    }

    <main #main [class.side-by-side]="sideBySide()">
      <section class="panel" [style.flex]="paneFlex()">
        <div class="panel-head">
          <span class="title">Terminal</span>
          <span class="sub">your session — nothing is typed here while you are using it</span>
        </div>
        <div class="panel-body term"><kb-terminal /></div>
      </section>

      @if (monitoring()) {
        <div class="splitter" [class.dragging]="dragging()" (pointerdown)="startDrag($event)"
             title="Drag to resize"><span></span></div>

        <section class="panel">
          <div class="panel-head">
            <span class="title">Pods</span>
            <span class="sub">polled in the session above, and paused while you use it</span>
          </div>
          <div class="panel-body">
            <kb-pods [pods]="snapshot()?.pods ?? []" [updatedAt]="updatedAt()"
                     [(issuesOnly)]="issuesOnly"
                     [commands]="api.commands()" [selected]="drawerPod()"
                     (action)="openPodCommand($event)" />
          </div>
        </section>
      }
    </main>

    @if (activeCommand()) {
      <kb-output [commands]="api.commands()" [activeId]="activeCommand()"
                 [pod]="drawerPod()" [result]="result()" [running]="runningCommand()"
                 [error]="commandError()" [(tail)]="tail"
                 (run)="runCommand($event)" (closed)="closeOutput()" />
    }

    <footer class="statusbar">
      <span class="state" [class]="tone()"><span class="dot"></span>{{ stateLabel() }}</span>
      <span class="detail">{{ stateHint() }}</span>
      <span class="spacer"></span>
      @if (monitoring()) {
        <span class="detail" [class.warn]="stale()">updated {{ updatedAgo() }}</span>
      }
      <span class="detail">127.0.0.1 only · no telemetry</span>
    </footer>
  `,
  styles: [`
    :host {
      display: flex; flex-direction: column; height: 100vh;
      background: var(--bg); overflow: hidden;
    }

    /* ── app bar ──────────────────────────────────────────────────────── */
    .appbar {
      display: flex; align-items: center; gap: 12px; flex: none; flex-wrap: wrap;
      padding: 8px 12px;
      background: var(--panel);
      border-bottom: 1px solid var(--border);
      box-shadow: var(--shadow);
      position: relative; z-index: 2;
    }
    .mark { display: block; }
    .brand { font-weight: 700; font-size: 14px; letter-spacing: -.2px; margin-left: -6px; }
    .spacer { flex: 1; }

    .field {
      display: inline-flex; align-items: center; gap: 7px;
      font-family: var(--mono); font-size: 12px;
      padding: 3px 10px; border-radius: var(--radius);
      background: var(--panel-2); border: 1px solid var(--border);
    }
    .field .label {
      font-family: var(--sans); font-size: 10px; text-transform: uppercase;
      letter-spacing: .5px; color: var(--faint);
    }

    .state {
      display: inline-flex; align-items: center; gap: 7px;
      font-size: 12px; font-weight: 500; white-space: nowrap;
    }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; flex: none; }
    .state.ok { color: var(--ok); }
    .state.ok .dot { animation: pulse 2s ease-in-out infinite; }
    .state.warn { color: var(--warn); }
    .state.bad { color: var(--bad); }
    .state.idle { color: var(--faint); }
    @keyframes pulse { 50% { opacity: .3; } }

    /* ── stat strip ───────────────────────────────────────────────────── */
    .stats {
      display: grid; flex: none;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      background: var(--panel);
      border-bottom: 1px solid var(--border);
    }
    /* Separators live on the cells, not in a grid gap: wrapped rows would
       otherwise leave a gap-coloured hole where the sixth card is not. */
    .stat {
      display: flex; flex-direction: column; gap: 1px; text-align: left;
      padding: 7px 12px; background: var(--panel);
      border: none; border-right: 1px solid var(--border); border-radius: 0;
    }
    .stat:last-child { border-right: none; }
    .stat .k {
      font-size: 10px; text-transform: uppercase; letter-spacing: .5px; color: var(--faint);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .stat .v { font-size: 17px; font-weight: 600; font-variant-numeric: tabular-nums; }
    .stat .v.small { font-size: 13px; padding: 3px 0 1px; }
    .stat .v.ok { color: var(--ok); }
    .stat .v.bad { color: var(--bad); }
    .stat .v.warn { color: var(--warn); }
    .clickable { cursor: pointer; }
    .clickable:hover { background: var(--panel-2); }
    .clickable.active { background: var(--bad-bg); box-shadow: inset 0 -2px 0 var(--bad); }

    /* ── command ribbon ───────────────────────────────────────────────── */
    .ribbon {
      display: flex; align-items: center; gap: 6px 22px; flex: none; flex-wrap: wrap;
      padding: 6px 12px;
      background: var(--panel-2);
      border-bottom: 1px solid var(--border);
    }
    .group { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .group-label {
      font-size: 10px; text-transform: uppercase; letter-spacing: .6px;
      color: var(--faint); margin-right: 4px; white-space: nowrap;
    }
    .chip {
      font-size: 11.5px; padding: 3px 10px; border-radius: 100px;
      color: var(--text); background: var(--panel); border: 1px solid var(--border-2);
      cursor: pointer; white-space: nowrap; list-style: none;
    }
    .chip:hover { color: var(--accent); border-color: var(--accent); background: var(--panel); }
    .chip.active { color: var(--panel); background: var(--accent); border-color: var(--accent); }

    .more { position: relative; }
    .more summary::-webkit-details-marker { display: none; }
    .more summary { color: var(--muted); }
    .more[open] summary { color: var(--accent); border-color: var(--accent); }
    .menu {
      position: absolute; top: calc(100% + 4px); left: 0; z-index: 30;
      min-width: 260px; padding: 4px;
      background: var(--panel); border: 1px solid var(--border-2); border-radius: var(--radius);
      box-shadow: 0 6px 20px rgba(10, 16, 24, .16);
    }
    .menu button {
      display: flex; flex-direction: column; align-items: flex-start; gap: 1px;
      width: 100%; text-align: left;
      padding: 6px 9px; border: none; border-radius: 4px; background: none;
    }
    .menu button:hover { background: var(--accent-bg); }
    .menu button span { font-size: 12px; color: var(--text); }
    .menu button small { font-size: 10.5px; color: var(--faint); white-space: normal; line-height: 1.3; }

    /* ── banner / steps ───────────────────────────────────────────────── */
    .banner {
      display: flex; align-items: center; gap: 9px; flex: none;
      padding: 8px 12px; font-size: 12.5px;
      border-bottom: 1px solid var(--border);
    }
    .banner.bad { background: var(--bad-bg); color: var(--bad); }
    .banner.warn { background: var(--warn-bg); color: var(--warn); }
    .banner .icon {
      flex: none; width: 16px; height: 16px; border-radius: 50%;
      display: grid; place-items: center; font-size: 11px; font-weight: 700;
      background: currentColor; color: var(--panel);
    }

    .steps {
      display: flex; flex-wrap: wrap; gap: 6px 26px; flex: none;
      margin: 0; padding: 8px 12px 8px 30px;
      background: var(--panel-2); border-bottom: 1px solid var(--border);
      color: var(--muted); font-size: 12px;
    }
    .steps li { margin: 0; }
    .steps code { font-family: var(--mono); color: var(--text); }

    /* ── panels ───────────────────────────────────────────────────────── */
    main {
      flex: 1; min-height: 0; display: flex; flex-direction: column;
      padding: 10px; gap: 0;
    }
    main.side-by-side { flex-direction: row; }

    .panel {
      display: flex; flex-direction: column; min-height: 0; min-width: 0;
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      box-shadow: var(--shadow);
      overflow: hidden;
    }
    main:not(.side-by-side) .panel:last-child { flex: 1; }
    main.side-by-side .panel:last-child { flex: 1; }

    .panel-head {
      display: flex; align-items: baseline; gap: 10px; flex: none;
      padding: 7px 12px;
      background: var(--panel-2);
      border-bottom: 1px solid var(--border);
    }
    .panel-head .title {
      font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .6px;
    }
    .panel-head .sub {
      font-size: 11.5px; color: var(--faint);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .panel-body { flex: 1; min-height: 0; }
    .panel-body.term { background: var(--term-bg); }

    .splitter {
      flex: none; display: grid; place-items: center; touch-action: none;
    }
    main:not(.side-by-side) .splitter { height: 10px; cursor: row-resize; }
    main.side-by-side .splitter { width: 10px; cursor: col-resize; }
    .splitter span { border-radius: 2px; background: var(--border-2); transition: background .12s ease; }
    main:not(.side-by-side) .splitter span { width: 30px; height: 3px; }
    main.side-by-side .splitter span { width: 3px; height: 30px; }
    .splitter:hover span, .splitter.dragging span { background: var(--accent); }

    /* ── status bar ───────────────────────────────────────────────────── */
    .statusbar {
      display: flex; align-items: center; gap: 12px; flex: none; flex-wrap: wrap;
      padding: 5px 12px;
      background: var(--panel); border-top: 1px solid var(--border);
      font-size: 11.5px; color: var(--faint);
    }
    .statusbar .state { font-size: 11.5px; }
    .detail { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .detail.warn { color: var(--warn); }

    /* ── responsive ───────────────────────────────────────────────────── */
    @media (max-width: 860px) {
      .stats { grid-template-columns: repeat(3, minmax(0, 1fr)); }
      .stat:nth-child(n+4) { border-top: 1px solid var(--border); }
      .panel-head .sub { display: none; }
      main { padding: 8px; }
    }
    @media (max-width: 620px) {
      .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .stat:nth-child(n+3) { border-top: 1px solid var(--border); }
      /* One scrollable strip beats a four-line wall of chips on a phone. */
      .ribbon { flex-wrap: nowrap; overflow-x: auto; gap: 6px 16px; padding: 6px 10px; }
      .group { flex-wrap: nowrap; }
      .field { display: none; }
      .steps { flex-direction: column; gap: 3px; }
      .statusbar .detail:last-child { display: none; }
      main { padding: 0; }
      .panel { border-radius: 0; border-left: none; border-right: none; }
    }
  `],
})
export class AppComponent implements OnInit, OnDestroy {
  readonly api = inject(KubastionService);
  readonly snapshot = this.api.snapshot;

  @ViewChild('main', { static: true }) main!: ElementRef<HTMLElement>;

  readonly issuesOnly = signal(false);
  readonly dragging = signal(false);
  readonly wide = signal(matches(WIDE));

  /**
   * Side by side and stacked want different proportions, so each orientation
   * keeps its own remembered size instead of fighting over one number.
   */
  private readonly splits = signal({ row: readSplit(true), col: readSplit(false) });

  private readonly media = window.matchMedia(WIDE);
  private readonly onMedia = () => this.wide.set(this.media.matches);
  private readonly now = signal(Date.now());
  private readonly clock = setInterval(() => this.now.set(Date.now()), 1000);

  /** Command window state. Empty id means the window is closed. */
  readonly activeCommand = signal('');
  readonly drawerPod = signal<string | null>(null);
  readonly result = signal<RunResult | null>(null);
  readonly runningCommand = signal(false);
  readonly commandError = signal('');
  readonly tail = signal(200);

  ngOnInit(): void {
    this.api.connect();
    this.api.loadCommands();
    this.media.addEventListener('change', this.onMedia);
    window.addEventListener('keydown', this.onKey);
  }

  ngOnDestroy(): void {
    this.media.removeEventListener('change', this.onMedia);
    window.removeEventListener('keydown', this.onKey);
    clearInterval(this.clock);
  }

  private readonly onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && this.activeCommand()) {
      this.closeOutput();
    }
  };

  // -------------------------------------------------------------- commands

  /** Quick commands of one scope go in the ribbon; the rest fold into "More". */
  quick(scope: 'NAMESPACE' | 'CLUSTER'): CommandInfo[] {
    return this.api.commands().filter((command) => command.scope === scope && command.quick);
  }

  more(scope: 'NAMESPACE' | 'CLUSTER'): CommandInfo[] {
    return this.api.commands().filter((command) => command.scope === scope && !command.quick);
  }

  /** A pod command from a row button, or the row itself (which means logs). */
  openPodCommand(request: { id: string; pod: string }): void {
    this.drawerPod.set(request.pod);
    this.runCommand(request.id);
  }

  /** A namespace or cluster command from the ribbon. */
  openCommand(id: string): void {
    this.drawerPod.set(null);
    this.runCommand(id);
  }

  runCommand(id: string): void {
    if (!id) {
      return;
    }
    this.activeCommand.set(id);
    this.runningCommand.set(true);
    this.commandError.set('');
    this.result.set(null);

    const pod = this.drawerPod() ?? undefined;
    this.api.runCommand({ id, pod, tailLines: this.tail() })
      .then((result) => {
        // A slow command must not overwrite whatever you opened after it.
        if (this.activeCommand() === id) {
          this.result.set(result);
        }
      })
      .catch((error: Error) => {
        if (this.activeCommand() === id) {
          this.commandError.set(error.message);
        }
      })
      .finally(() => this.runningCommand.set(false));
  }

  closeOutput(): void {
    this.activeCommand.set('');
    this.drawerPod.set(null);
    this.result.set(null);
    this.commandError.set('');
  }

  // ------------------------------------------------------------------ state

  readonly monitoring = computed(() => this.snapshot()?.state !== 'IDLE' && !!this.snapshot());

  readonly sideBySide = computed(() => this.wide() && this.monitoring());

  readonly split = computed(() =>
    this.sideBySide() ? this.splits().row : this.splits().col);

  readonly paneFlex = computed(() =>
    this.monitoring() ? `0 0 ${this.split()}%` : '1 1 auto');

  readonly total = computed(() => this.snapshot()?.pods.length ?? 0);
  readonly issues = computed(() => this.snapshot()?.pods.filter((pod) => !pod.healthy).length ?? 0);
  readonly restarts = computed(() =>
    this.snapshot()?.pods.reduce((sum, pod) => sum + pod.restarts, 0) ?? 0);
  readonly updatedAt = computed(() => this.snapshot()?.updatedAt ?? 0);

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

  /** Past a few polling rounds the grid is no longer telling you the truth. */
  readonly stale = computed(() => {
    const at = this.updatedAt();
    return at > 0 && this.now() - at > 15_000;
  });

  /**
   * Only speaks up when something is off. A pause is expected and explains
   * itself in a calmer colour than a failure.
   */
  readonly banner = computed(() => {
    if (!this.api.backendOnline()) {
      return { tone: 'bad', text: 'Backend unreachable. Start kubastion on port 8080.' };
    }
    const snap = this.snapshot();
    if (snap?.state === 'ERROR' && snap.message) {
      return { tone: 'bad', text: snap.message };
    }
    if (snap?.state === 'PAUSED' && snap.message) {
      return { tone: 'warn', text: snap.message };
    }
    return null;
  });

  readonly stateLabel = computed(() => {
    if (!this.api.backendOnline()) { return 'Offline'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'Monitoring';
      case 'PAUSED': return 'Paused';
      case 'ERROR': return 'Error';
      default: return 'Terminal';
    }
  });

  readonly stateHint = computed(() => {
    if (!this.api.backendOnline()) { return 'No connection to the local backend'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'Polling pods in the session you opened';
      case 'PAUSED': return 'The terminal is yours right now';
      case 'ERROR': return 'Retrying — the reason is above';
      default: return 'Terminal only: nothing is being polled';
    }
  });

  readonly tone = computed(() => {
    if (!this.api.backendOnline()) { return 'warn'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'ok';
      case 'PAUSED': return 'warn';
      case 'ERROR': return 'bad';
      default: return 'idle';
    }
  });

  // ----------------------------------------------------------------- resize

  startDrag(event: PointerEvent): void {
    event.preventDefault();
    const bounds = this.main.nativeElement.getBoundingClientRect();
    const horizontal = this.sideBySide();
    this.dragging.set(true);

    const key = horizontal ? 'row' : 'col';
    const move = (moved: PointerEvent) => {
      const percent = horizontal
        ? ((moved.clientX - bounds.left) / bounds.width) * 100
        : ((moved.clientY - bounds.top) / bounds.height) * 100;
      const size = Math.min(85, Math.max(15, Math.round(percent)));
      this.splits.set({ ...this.splits(), [key]: size });
    };
    const release = () => {
      this.dragging.set(false);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', release);
      try {
        localStorage.setItem(`${SPLIT_KEY}.${key}`, String(this.split()));
      } catch {
        // private browsing or blocked storage: the default split is fine
      }
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', release);
  }
}

function matches(query: string): boolean {
  try {
    return window.matchMedia(query).matches;
  } catch {
    return false;
  }
}

function readSplit(horizontal: boolean): number {
  try {
    const stored = Number(localStorage.getItem(`${SPLIT_KEY}.${horizontal ? 'row' : 'col'}`));
    if (stored >= 15 && stored <= 85) {
      return stored;
    }
  } catch {
    // fall through to the default
  }
  return horizontal ? 50 : 58;
}
