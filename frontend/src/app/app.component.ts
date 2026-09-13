import {
  Component, ElementRef, OnInit, ViewChild, computed, inject, signal,
} from '@angular/core';
import { KubastionService } from './kubastion.service';
import { PodsComponent } from './pods.component';
import { TerminalComponent } from './terminal.component';

const SPLIT_KEY = 'kubastion.split';

/**
 * Layout: the terminal owns the stage, and the pod table appears underneath
 * once monitoring is on.
 *
 * The order of operations is the real one: first you log in by hand in the
 * terminal (key, ssh, menu, destination), then you press "Start monitoring".
 */
@Component({
  selector: 'kb-root',
  standalone: true,
  imports: [TerminalComponent, PodsComponent],
  template: `
    <header>
      <img class="mark" src="assets/kubastion-mark.svg" alt="" width="22" height="22">
      <span class="brand">kubastion</span>
      @if (snapshot()?.namespace; as namespace) {
        <span class="ns" title="Namespace being watched">{{ namespace }}</span>
      }

      <span class="spacer"></span>

      <span class="state" [class]="stateClass()" [title]="stateHint()">
        <span class="dot"></span>{{ stateLabel() }}
      </span>

      @if (monitoring()) {
        <button type="button" (click)="api.stopMonitoring()">Stop monitoring</button>
      } @else {
        <button type="button" class="primary" (click)="api.startMonitoring()"
                [disabled]="!api.backendOnline()">Start monitoring</button>
      }
    </header>

    @if (banner(); as text) {
      <div class="banner">
        <span class="banner-icon">!</span>{{ text }}
      </div>
    }

    <main #main>
      <section class="pane" [style.flex]="monitoring() ? '0 0 ' + split() + '%' : '1 1 auto'">
        <kb-terminal />
      </section>

      @if (monitoring()) {
        <div class="splitter" [class.dragging]="dragging()" (pointerdown)="startDrag($event)"
             title="Drag to resize"><span></span></div>

        <section class="pane pods">
          <kb-pods [pods]="snapshot()?.pods ?? []" [updatedAt]="snapshot()?.updatedAt ?? 0" />
        </section>
      }
    </main>

    @if (!monitoring() && api.backendOnline()) {
      <footer class="hint">
        <span class="step"><b>1</b> Load your key and connect — ssh, gateway menu, destination.</span>
        <span class="step"><b>2</b> Check that <code>kubectl</code> answers in that session.</span>
        <span class="step"><b>3</b> Press <b>Start monitoring</b> to poll pods every few seconds.</span>
      </footer>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100vh; }

    header {
      display: flex; align-items: center; gap: 14px;
      padding: 9px 14px; flex: none;
      background: var(--surface);
      border-bottom: 1px solid var(--border);
    }
    .mark { display: block; margin-right: -6px; }
    .brand {
      font-weight: 700; letter-spacing: .3px; color: var(--accent);
    }
    .ns {
      font-size: 12px; color: var(--muted);
      padding: 2px 9px; border-radius: 100px;
      background: var(--surface-2); border: 1px solid var(--border);
    }
    .ns::before { content: 'ns '; opacity: .6; }
    .spacer { flex: 1; }

    .state { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; }
    .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: currentColor; box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 18%, transparent);
    }
    .state.ok { color: var(--ok); }
    .state.ok .dot { animation: pulse 2s ease-in-out infinite; }
    .state.warn { color: var(--warn); }
    .state.bad { color: var(--bad); }
    .state.idle { color: var(--muted); }
    @keyframes pulse { 50% { opacity: .35; } }

    button.primary { border-color: var(--accent); color: var(--accent); }
    button.primary:not(:disabled):hover { background: rgba(76, 154, 255, .12); }

    .banner {
      display: flex; align-items: center; gap: 9px;
      padding: 9px 14px; flex: none;
      background: rgba(229, 83, 75, .1);
      border-bottom: 1px solid rgba(229, 83, 75, .3);
      color: var(--bad); font-size: 12.5px;
    }
    .banner-icon {
      flex: none; width: 16px; height: 16px; border-radius: 50%;
      display: grid; place-items: center; font-size: 11px; font-weight: 700;
      background: rgba(229, 83, 75, .22);
    }

    main { flex: 1; min-height: 0; display: flex; flex-direction: column; }
    .pane { min-height: 0; }
    .pods {
      flex: 1; display: flex; flex-direction: column;
      background: var(--surface);
    }

    .splitter {
      flex: none; height: 9px; cursor: row-resize;
      display: grid; place-items: center;
      background: var(--surface);
      border-top: 1px solid var(--border);
      border-bottom: 1px solid var(--border);
      touch-action: none;
    }
    .splitter span {
      width: 34px; height: 2px; border-radius: 2px; background: var(--border);
      transition: background .12s ease;
    }
    .splitter:hover span, .splitter.dragging span { background: var(--accent); }

    .hint {
      display: flex; flex-wrap: wrap; gap: 8px 22px; flex: none;
      padding: 9px 14px;
      background: var(--surface);
      border-top: 1px solid var(--border);
      color: var(--muted); font-size: 12px;
    }
    .step { display: inline-flex; align-items: center; gap: 7px; }
    .step b:first-child {
      width: 16px; height: 16px; border-radius: 50%;
      display: grid; place-items: center; font-size: 10px;
      background: var(--surface-2); color: var(--text); border: 1px solid var(--border);
    }
    .hint code { color: var(--text); }
  `],
})
export class AppComponent implements OnInit {
  readonly api = inject(KubastionService);
  readonly snapshot = this.api.snapshot;

  @ViewChild('main', { static: true }) main!: ElementRef<HTMLElement>;

  /** Terminal height as a percentage of the workspace; remembered per browser. */
  readonly split = signal(readSplit());
  readonly dragging = signal(false);

  ngOnInit(): void {
    this.api.connect();
  }

  readonly monitoring = computed(() => {
    const state = this.snapshot()?.state;
    return state === 'MONITORING' || state === 'ERROR';
  });

  /** Only shows problems: when everything works it stays invisible. */
  readonly banner = computed(() => {
    if (!this.api.backendOnline()) {
      return 'Backend unreachable. Start kubastion on port 8080.';
    }
    const snap = this.snapshot();
    return snap?.state === 'ERROR' && snap.message ? snap.message : null;
  });

  readonly stateLabel = computed(() => {
    if (!this.api.backendOnline()) { return 'offline'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'monitoring';
      case 'ERROR': return 'error';
      default: return 'terminal';
    }
  });

  readonly stateHint = computed(() => {
    if (!this.api.backendOnline()) { return 'No connection to the local backend'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'Polling pods on the session you opened';
      case 'ERROR': return 'Polling is retrying — see the message above';
      default: return 'Terminal only: nothing is being polled yet';
    }
  });

  readonly stateClass = computed(() => {
    if (!this.api.backendOnline()) { return 'warn'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'ok';
      case 'ERROR': return 'bad';
      default: return 'idle';
    }
  });

  startDrag(event: PointerEvent): void {
    event.preventDefault();
    const bounds = this.main.nativeElement.getBoundingClientRect();
    this.dragging.set(true);

    const move = (moved: PointerEvent) => {
      const percent = ((moved.clientY - bounds.top) / bounds.height) * 100;
      this.split.set(Math.min(85, Math.max(15, Math.round(percent))));
    };
    const release = () => {
      this.dragging.set(false);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', release);
      try {
        localStorage.setItem(SPLIT_KEY, String(this.split()));
      } catch {
        // private browsing or blocked storage: the default split is fine
      }
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', release);
  }
}

function readSplit(): number {
  try {
    const stored = Number(localStorage.getItem(SPLIT_KEY));
    return stored >= 15 && stored <= 85 ? stored : 58;
  } catch {
    return 58;
  }
}
