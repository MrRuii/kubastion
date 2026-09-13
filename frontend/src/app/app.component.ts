import { Component, OnInit, computed, inject } from '@angular/core';
import { KubastionService } from './kubastion.service';
import { PodsComponent } from './pods.component';
import { TerminalComponent } from './terminal.component';

/**
 * Layout: il terminale occupa la scena, la tabella dei pod compare sotto
 * quando accendi il monitoraggio.
 *
 * L'ordine delle operazioni e' quello reale: prima ti colleghi a mano nel
 * terminale (chiave, ssh, menu, destinazione), poi premi "start monitoring".
 */
@Component({
  selector: 'kb-root',
  standalone: true,
  imports: [TerminalComponent, PodsComponent],
  template: `
    <header>
      <span class="brand">kubastion</span>
      @if (snapshot(); as snap) {
        <span class="ns">{{ snap.namespace }}</span>
      }
      <span class="spacer"></span>

      <span class="state" [class]="stateClass()">
        <span class="dot"></span>{{ stateLabel() }}
      </span>

      @if (monitoring()) {
        <button type="button" (click)="api.stopMonitoring()">stop monitoring</button>
      } @else {
        <button type="button" class="primary" (click)="api.startMonitoring()"
                [disabled]="!api.backendOnline()">start monitoring</button>
      }
    </header>

    @if (banner(); as text) {
      <div class="banner">{{ text }}</div>
    }

    <main [class.split]="monitoring()">
      <section class="terminal">
        <kb-terminal />
      </section>

      @if (monitoring()) {
        <section class="pods">
          <div class="pods-head">
            pod
            @if (snapshot(); as snap) {
              <span class="count">{{ snap.pods.length }}</span>
            }
          </div>
          <kb-pods [pods]="snapshot()?.pods ?? []" />
        </section>
      }
    </main>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100vh; }

    header {
      display: flex; align-items: center; gap: 14px;
      padding: 8px 14px;
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      flex: none;
    }
    .brand { font-weight: 700; letter-spacing: .5px; color: var(--accent); }
    .ns { color: var(--muted); }
    .ns::before { content: 'ns: '; }
    .spacer { flex: 1; }
    .state { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
    .state.ok { color: var(--ok); }
    .state.warn { color: var(--warn); }
    .state.bad { color: var(--bad); }
    .state.idle { color: var(--muted); }
    button.primary { border-color: var(--accent); color: var(--accent); }

    .banner {
      padding: 8px 14px; flex: none;
      background: rgba(229, 83, 75, .1);
      border-bottom: 1px solid rgba(229, 83, 75, .3);
      color: var(--bad); font-size: 12.5px;
    }

    main { flex: 1; min-height: 0; display: flex; flex-direction: column; }
    .terminal { flex: 1; min-height: 0; }
    main.split .terminal { flex: 1 1 55%; }
    .pods {
      flex: 1 1 45%; min-height: 0;
      display: flex; flex-direction: column;
      background: var(--surface);
      border-top: 1px solid var(--border);
    }
    .pods-head {
      padding: 7px 14px; flex: none;
      border-bottom: 1px solid var(--border);
      font-size: 11px; text-transform: uppercase; letter-spacing: .6px; color: var(--muted);
    }
    .count {
      margin-left: 8px; color: var(--text);
      background: var(--surface-2); border-radius: 100px; padding: 1px 8px;
    }
  `],
})
export class AppComponent implements OnInit {
  readonly api = inject(KubastionService);
  readonly snapshot = this.api.snapshot;

  ngOnInit(): void {
    this.api.connect();
  }

  readonly monitoring = computed(() => {
    const state = this.snapshot()?.state;
    return state === 'MONITORING' || state === 'ERROR';
  });

  /** Mostra solo i problemi: quando tutto va, resta invisibile. */
  readonly banner = computed(() => {
    if (!this.api.backendOnline()) {
      return 'Backend non raggiungibile. Avvia kubastion sulla porta 8080.';
    }
    const snap = this.snapshot();
    return snap?.state === 'ERROR' && snap.message ? snap.message : null;
  });

  readonly stateLabel = computed(() => {
    if (!this.api.backendOnline()) { return 'offline'; }
    switch (this.snapshot()?.state) {
      case 'MONITORING': return 'monitoring';
      case 'ERROR': return 'errore';
      default: return 'terminale';
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
}
