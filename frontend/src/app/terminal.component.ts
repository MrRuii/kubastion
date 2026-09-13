import {
  AfterViewInit, Component, ElementRef, NgZone, OnDestroy, ViewChild, inject,
} from '@angular/core';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { wsScheme } from './kubastion.service';

/**
 * Terminale vero nel browser, collegato a una shell locale tramite PTY.
 *
 * Qui dentro fai il login come lo faresti sempre: carichi la chiave, lanci
 * ssh, attraversi il menu del gateway, scegli la destinazione. kubastion non
 * automatizza niente di tutto cio' — ed e' per questo che funziona con
 * qualunque gateway, per quanto strano sia.
 */
@Component({
  selector: 'kb-terminal',
  standalone: true,
  template: `<div #host class="term"></div>`,
  styles: [`
    :host { display: block; height: 100%; min-height: 0; }
    .term { height: 100%; padding: 6px 8px; }
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

  ngAfterViewInit(): void {
    // xterm emette moltissimi eventi: fuori dalla zone per non far girare a
    // vuoto il change detection a ogni carattere.
    this.zone.runOutsideAngular(() => {
      const terminal = new Terminal({
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: 13,
        cursorBlink: true,
        scrollback: 5000,
        theme: {
          background: '#0f1419',
          foreground: '#d5dde6',
          cursor: '#4c9aff',
          selectionBackground: '#2a3f5f',
        },
      });
      const fit = new FitAddon();
      terminal.loadAddon(fit);
      terminal.open(this.host.nativeElement);
      fit.fit();

      this.terminal = terminal;
      this.fit = fit;

      terminal.onData((data) => this.send({ type: 'input', data }));

      this.observer = new ResizeObserver(() => this.refit());
      this.observer.observe(this.host.nativeElement);

      this.connect();
    });
  }

  ngOnDestroy(): void {
    this.closing = true;
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
      this.terminal?.writeln('\r\n\x1b[33m[kubastion] backend non raggiungibile, riprovo…\x1b[0m');
      setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 10_000);
    };
  }

  /** Adatta il terminale allo spazio e informa il PTY della nuova dimensione. */
  private refit(): void {
    try {
      this.fit?.fit();
    } catch {
      // il contenitore puo' essere temporaneamente a dimensione zero
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
