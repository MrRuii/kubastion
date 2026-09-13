import { Component, OnInit, computed, inject } from '@angular/core';
import { KubastionService } from './kubastion.service';
import { PodsComponent } from './pods.component';

@Component({
  selector: 'kb-root',
  standalone: true,
  imports: [PodsComponent],
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
    </header>

    @if (message(); as text) {
      <div class="banner">{{ text }}</div>
    }

    <kb-pods />
  `,
  styles: [`
    header {
      display: flex; align-items: center; gap: 14px;
      padding: 10px 16px;
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      position: sticky; top: 0; z-index: 5;
    }
    .brand { font-weight: 700; letter-spacing: .5px; color: var(--accent); }
    .ns { color: var(--muted); }
    .ns::before { content: 'ns: '; }
    .spacer { flex: 1; }
    .state { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
    .state.ok { color: var(--ok); }
    .state.warn { color: var(--warn); }
    .state.idle { color: var(--muted); }
    .banner {
      padding: 9px 16px;
      background: rgba(232, 176, 75, .1);
      border-bottom: 1px solid rgba(232, 176, 75, .3);
      color: var(--warn);
      font-size: 12.5px;
    }
  `],
})
export class AppComponent implements OnInit {
  private readonly api = inject(KubastionService);

  readonly snapshot = this.api.snapshot;

  ngOnInit(): void {
    this.api.connect();
  }

  /** Il banner mostra solo i problemi: quando tutto va, resta invisibile. */
  readonly message = computed(() => {
    if (!this.api.socketOpen()) {
      return 'Backend non raggiungibile. Avvia kubastion sulla porta 8080.';
    }
    const snap = this.snapshot();
    return snap && snap.connection !== 'CONNECTED' && snap.message ? snap.message : null;
  });

  readonly stateLabel = computed(() => {
    if (!this.api.socketOpen()) { return 'offline'; }
    switch (this.snapshot()?.connection) {
      case 'CONNECTED': return 'live';
      case 'RECONNECTING': return 'riconnessione';
      default: return 'avvio';
    }
  });

  readonly stateClass = computed(() => {
    if (!this.api.socketOpen()) { return 'warn'; }
    switch (this.snapshot()?.connection) {
      case 'CONNECTED': return 'ok';
      case 'RECONNECTING': return 'warn';
      default: return 'idle';
    }
  });
}
