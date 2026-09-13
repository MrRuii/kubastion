import { Component, OnDestroy, computed, input, model, output, signal } from '@angular/core';
import { CommandInfo, RunResult } from './models';

const TAIL_CHOICES = [50, 200, 1000, 5000];

/**
 * The window a command's output opens in.
 *
 * Deliberately a panel over the console rather than a new browser window: the
 * table underneath keeps updating while you read, and closing it puts you back
 * exactly where you were. The command that ran is always printed above the
 * output — you should never have to guess what kubastion typed for you.
 */
@Component({
  selector: 'kb-output',
  standalone: true,
  template: `
    <div class="scrim" (click)="closed.emit()"></div>

    <aside class="drawer" role="dialog" aria-label="Command output">
      <header>
        <div class="heading">
          <span class="title">{{ result()?.title || label() }}</span>
          @if (pod()) { <span class="kind">pod</span> }
        </div>
        <button type="button" class="icon" title="Close" (click)="closed.emit()">×</button>
      </header>

      @if (pod(); as name) {
        <nav class="tabs">
          @for (command of podCommands(); track command.id) {
            <button type="button" [class.active]="command.id === activeId()"
                    [title]="command.description" (click)="run.emit(command.id)">
              {{ command.label }}
            </button>
          }
        </nav>
      }

      <div class="controls">
        @if (active()?.tailable) {
          <label class="tail">
            lines
            <select (change)="onTail($event)">
              @for (choice of tailChoices; track choice) {
                <option [value]="choice" [selected]="choice === tail()">{{ choice }}</option>
              }
            </select>
          </label>
        }
        <button type="button" (click)="run.emit(activeId())" [disabled]="running()">
          {{ running() ? 'Running…' : 'Re-run' }}
        </button>
        <span class="spacer"></span>
        <label class="check">
          <input type="checkbox" [checked]="wrap()" (change)="toggleWrap()"> wrap
        </label>
        <button type="button" (click)="copy()" [disabled]="!result()?.output">
          {{ copied() ? 'copied' : 'copy' }}
        </button>
      </div>

      @if (result(); as shown) {
        <code class="ran">{{ shown.command }}</code>
      }

      <div class="body">
        @if (running()) {
          <p class="note">Running in your session…</p>
        } @else if (error()) {
          <p class="note bad">{{ error() }}</p>
        } @else if (output()) {
          <pre [class.wrap]="wrap()">{{ output() }}</pre>
        } @else if (result()) {
          <p class="note">The command returned nothing.</p>
        }
      </div>

      <footer>
        @if (result(); as shown) {
          <span>{{ lines() }} {{ lines() === 1 ? 'line' : 'lines' }}</span>
          <span class="spacer"></span>
          <span>ran at {{ clock(shown.ranAt) }}</span>
        } @else {
          <span>&nbsp;</span>
        }
      </footer>
    </aside>
  `,
  styles: [`
    :host { position: fixed; inset: 0; z-index: 40; display: block; }
    .scrim { position: absolute; inset: 0; background: rgba(10, 16, 24, .38); }

    .drawer {
      position: absolute; top: 0; right: 0; bottom: 0;
      width: min(760px, 94vw);
      display: flex; flex-direction: column;
      background: var(--panel);
      border-left: 1px solid var(--border);
      box-shadow: -8px 0 28px rgba(10, 16, 24, .18);
      animation: slide .16s ease-out;
    }
    @keyframes slide { from { transform: translateX(16px); opacity: .4; } }

    header {
      display: flex; align-items: center; gap: 10px; flex: none;
      padding: 10px 12px;
      border-bottom: 1px solid var(--border);
      background: var(--panel-2);
    }
    .heading { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
    .title {
      font-family: var(--mono); font-size: 13px; font-weight: 600;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .kind {
      font-size: 10px; text-transform: uppercase; letter-spacing: .5px;
      color: var(--faint); border: 1px solid var(--border); border-radius: 100px;
      padding: 1px 7px;
    }
    .icon {
      margin-left: auto; flex: none;
      font-size: 17px; line-height: 1; padding: 2px 9px;
      background: none; border: none; color: var(--muted);
    }
    .icon:hover { color: var(--text); background: var(--panel-3); }

    .tabs {
      display: flex; flex: none; gap: 2px; padding: 6px 8px 0;
      border-bottom: 1px solid var(--border); background: var(--panel-2);
      overflow-x: auto;
    }
    .tabs button {
      border: 1px solid transparent; border-bottom: none; border-radius: 5px 5px 0 0;
      background: none; color: var(--muted); padding: 5px 12px; white-space: nowrap;
    }
    .tabs button:hover { color: var(--text); background: var(--panel-3); }
    .tabs button.active {
      color: var(--accent); background: var(--panel);
      border-color: var(--border); margin-bottom: -1px; padding-bottom: 6px;
    }

    .controls {
      display: flex; align-items: center; gap: 8px; flex: none; flex-wrap: wrap;
      padding: 8px 12px; border-bottom: 1px solid var(--border);
    }
    .spacer { flex: 1; }
    .tail, .check {
      display: inline-flex; align-items: center; gap: 6px;
      font-size: 11.5px; color: var(--muted);
    }
    .tail select { font-size: 12px; padding: 3px 6px; }
    .check input { margin: 0; accent-color: var(--accent); }

    .ran {
      flex: none; display: block;
      padding: 7px 12px;
      font-family: var(--mono); font-size: 11.5px; color: var(--muted);
      background: var(--panel-2); border-bottom: 1px solid var(--border);
      overflow-x: auto; white-space: pre;
    }

    .body { flex: 1; min-height: 0; overflow: auto; background: var(--term-bg); }
    pre {
      margin: 0; padding: 12px;
      font-family: var(--mono); font-size: 12px; line-height: 1.5;
      color: var(--term-fg); white-space: pre;
    }
    /* Hanging indent: a log entry starts hard against the left margin and its
       wrapped continuation is pushed in, so the eye finds where each entry
       begins instead of reading one undifferentiated block. Stack traces, which
       are already indented at the source, keep their own shape on top of it. */
    pre.wrap {
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      padding-left: calc(12px + 4ch);
      text-indent: -4ch;
    }
    .note { margin: 0; padding: 16px; color: var(--faint); }
    .note.bad { color: var(--bad); background: var(--bad-bg); }

    footer {
      display: flex; flex: none; gap: 10px;
      padding: 5px 12px;
      border-top: 1px solid var(--border); background: var(--panel-2);
      font-size: 11px; color: var(--faint);
    }

    @media (max-width: 620px) {
      .drawer { width: 100vw; border-left: none; }
    }
  `],
})
export class OutputComponent implements OnDestroy {
  readonly commands = input.required<CommandInfo[]>();
  readonly activeId = input.required<string>();
  readonly pod = input<string | null>(null);
  readonly result = input<RunResult | null>(null);
  readonly running = input(false);
  readonly error = input('');
  readonly tail = model(200);

  readonly run = output<string>();
  readonly closed = output<void>();

  readonly tailChoices = TAIL_CHOICES;

  /**
   * Wrapping is right for logs and wrong for everything else: a log line has no
   * columns to preserve and scrolling sideways through a stack trace is
   * miserable, while `describe` and the `get` tables only line up if left
   * alone. So it follows the command until you say otherwise, and then your
   * choice sticks.
   */
  private readonly wrapOverride = signal<boolean | null>(null);
  readonly wrap = computed(() => this.wrapOverride() ?? !!this.active()?.tailable);
  readonly copied = signal(false);
  private copyTimer?: ReturnType<typeof setTimeout>;

  ngOnDestroy(): void {
    clearTimeout(this.copyTimer);
  }

  readonly podCommands = computed(() => this.commands().filter((command) => command.needsPod));

  readonly active = computed(() =>
    this.commands().find((command) => command.id === this.activeId()) ?? null);

  readonly label = computed(() => this.active()?.label ?? 'Output');

  readonly output = computed(() => this.result()?.output ?? '');

  readonly lines = computed(() => {
    const text = this.output();
    return text ? text.split('\n').length : 0;
  });

  toggleWrap(): void {
    this.wrapOverride.set(!this.wrap());
  }

  onTail(event: Event): void {
    this.tail.set(Number((event.target as HTMLSelectElement).value));
    this.run.emit(this.activeId());
  }

  copy(): void {
    const output = this.result()?.output;
    if (!output) {
      return;
    }
    navigator.clipboard?.writeText(output).then(() => {
      this.copied.set(true);
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => this.copied.set(false), 1200);
    }, () => undefined);
  }

  clock(at: number): string {
    return new Date(at).toLocaleTimeString();
  }
}
