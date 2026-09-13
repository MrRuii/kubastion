import {
  AfterViewInit, Component, ElementRef, NgZone, OnDestroy, ViewChild, inject,
} from '@angular/core';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { wsScheme } from './kubastion.service';

/**
 * A real terminal in the browser, wired to a local shell through a PTY.
 *
 * You log in here exactly as you always do: load the key, run ssh, walk the
 * gateway menu, pick the destination. kubastion automates none of it — and that
 * is precisely why it works with any gateway, however odd.
 */
@Component({
  selector: 'kb-terminal',
  standalone: true,
  template: `<div #host class="term"></div>`,
  styles: [`
    /* The terminal keeps its own dark surface whatever the console theme is:
       a terminal is dark in every other tool you use, and the escape codes
       coming out of your shell assume exactly that. */
    :host { display: block; height: 100%; min-height: 0; background: var(--term-bg); }
    .term { height: 100%; padding: 8px 10px; }
  `],
})
export class TerminalComponent implements AfterViewInit, OnDestroy {
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;

  private readonly zone = inject(NgZone);
  private terminal?: Terminal;
  private fit?: FitAddon;
  private socket?: WebSocket;
  private observer?: ResizeObserver;
  private closing = false;
  private retryMs = 1000;
  private refitTimer?: ReturnType<typeof setTimeout>;

  ngAfterViewInit(): void {
    // xterm fires a great many events: keep it outside the zone so change
    // detection does not run on every single keystroke.
    this.zone.runOutsideAngular(() => {
      const terminal = new Terminal({
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: 13,
        lineHeight: 1.25,
        cursorBlink: true,
        scrollback: 5000,
        theme: {
          background: '#0f1419',
          foreground: '#d5dde6',
          cursor: '#4c9aff',
          cursorAccent: '#0f1419',
          selectionBackground: '#2a3f5f',
          black: '#171d24',
          red: '#e5534b',
          green: '#3ec18c',
          yellow: '#e8b04b',
          blue: '#4c9aff',
          magenta: '#b083f0',
          cyan: '#4dc2c2',
          white: '#d5dde6',
        },
      });
      const fit = new FitAddon();
      terminal.loadAddon(fit);
      terminal.open(this.host.nativeElement);
      fit.fit();

      this.terminal = terminal;
      this.fit = fit;

      terminal.onData((data) => this.send({ type: 'input', data }));

      // Copy and paste the way every other terminal on the machine does it.
      // Ctrl+C keeps meaning SIGINT when nothing is selected — interrupting a
      // command is what that key is for — and only copies when you have made a
      // selection, which is the one case where SIGINT is not what you wanted.
      terminal.attachCustomKeyEventHandler((event) => {
        if (event.type !== 'keydown' || !(event.ctrlKey || event.metaKey) || event.altKey) {
          return true;
        }
        const key = event.key.toLowerCase();
        if (key === 'c' && terminal.hasSelection()) {
          navigator.clipboard?.writeText(terminal.getSelection()).catch(() => undefined);
          terminal.clearSelection();
          return false;
        }
        if (key === 'v') {
          navigator.clipboard?.readText()
            .then((text) => { if (text) { this.send({ type: 'input', data: text }); } })
            .catch(() => undefined);
          return false;
        }
        return true;
      });

      // Middle-click and right-click paste, as on a Linux terminal.
      this.host.nativeElement.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        navigator.clipboard?.readText()
          .then((text) => { if (text) { this.send({ type: 'input', data: text }); } })
          .catch(() => undefined);
      });

      this.observer = new ResizeObserver(() => this.scheduleRefit());
      this.observer.observe(this.host.nativeElement);

      this.connect();
    });
  }

  ngOnDestroy(): void {
    this.closing = true;
    clearTimeout(this.refitTimer);
    this.observer?.disconnect();
    this.socket?.close();
    this.terminal?.dispose();
  }

  private connect(): void {
    const socket = new WebSocket(`${wsScheme()}://${location.host}/ws/terminal`);
    this.socket = socket;

    socket.onopen = () => {
      this.retryMs = 1000;
      this.refit();
    };
    socket.onmessage = (event) => this.terminal?.write(event.data as string);
    socket.onerror = () => socket.close();
    socket.onclose = () => {
      if (this.closing) {
        return;
      }
      this.terminal?.writeln('\r\n\x1b[33m[kubastion] backend unreachable, retrying…\x1b[0m');
      setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 10_000);
    };
  }

  /**
   * Dragging the splitter fires a resize per animation frame. Each one reflows
   * the remote console, so they are collapsed into one at the end of the drag.
   */
  private scheduleRefit(): void {
    clearTimeout(this.refitTimer);
    this.refitTimer = setTimeout(() => this.refit(), 120);
  }

  /** Fit the terminal to the space available and tell the PTY the new size. */
  private refit(): void {
    try {
      this.fit?.fit();
    } catch {
      // the container can briefly have zero size
    }
    const terminal = this.terminal;
    if (terminal) {
      this.send({ type: 'resize', cols: terminal.cols, rows: terminal.rows });
    }
  }

  private send(message: Record<string, unknown>): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    }
  }
}
