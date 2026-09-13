import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { PodsSnapshot } from './models';

/**
 * Unico punto di contatto col backend locale.
 *
 * Lo stato dei pod arriva via WebSocket: il backend spinge uno snapshot a ogni
 * evento del cluster, quindi qui non c'e' nessun polling. Se il socket cade
 * (backend riavviato, macchina in sospensione) si riconnette da solo.
 */
@Injectable({ providedIn: 'root' })
export class KubastionService {
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);

  /** Ultimo stato ricevuto. null finche' non arriva il primo messaggio. */
  readonly snapshot = signal<PodsSnapshot | null>(null);
  /** Stato del socket verso il backend, distinto dallo stato della connessione SSH. */
  readonly socketOpen = signal(false);

  private socket?: WebSocket;
  private retryMs = 1000;

  connect(): void {
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) {
      return;
    }
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}/ws/pods`);
    this.socket = socket;

    socket.onopen = () => this.zone.run(() => {
      this.socketOpen.set(true);
      this.retryMs = 1000;
    });

    socket.onmessage = (event) => this.zone.run(() => {
      try {
        this.snapshot.set(JSON.parse(event.data as string) as PodsSnapshot);
      } catch {
        // messaggio malformato: si ignora, il prossimo snapshot rimette a posto
      }
    });

    socket.onclose = () => this.zone.run(() => {
      this.socketOpen.set(false);
      // backoff fino a 10s: il backend potrebbe essere fermo
      setTimeout(() => this.connect(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 10_000);
    });

    socket.onerror = () => socket.close();
  }

  /** Log del pod, su richiesta. Testo grezzo, come lo restituisce kubectl. */
  logs(pod: string): Observable<string> {
    return this.http.get(`/api/logs?pod=${encodeURIComponent(pod)}`, { responseType: 'text' });
  }
}
