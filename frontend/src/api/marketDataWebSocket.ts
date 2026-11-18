import { getConfig } from '../config';
import type { Stock } from '../types/marketData';

export type Disconnect = () => void;

export interface MarketDataStreamHandlers {
  readonly onStock: (stock: Stock) => void;
  readonly onError: (message: string) => void;
}

/** The first frame the server expects; see MarketDataWebSocketHandler in marketdata. */
interface AuthFrame {
  readonly type: 'auth';
  readonly token: string;
}

function streamUrl(): string {
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const base = getConfig().wsBase || `${wsProtocol}//${window.location.host}`;
  return `${base.replace(/^wss?:/, wsProtocol)}/ws/marketdata`;
}

export function connectMarketDataStream(token: string, { onStock, onError }: MarketDataStreamHandlers): Disconnect {
  const ws = new WebSocket(streamUrl());

  ws.onopen = () => {
    const auth: AuthFrame = { type: 'auth', token };
    ws.send(JSON.stringify(auth));
  };
  // Same trust boundary as `request()`: frames are cast to the server contract, not validated.
  ws.onmessage = (event: MessageEvent<string>) => {
    onStock(JSON.parse(event.data) as Stock);
  };
  ws.onerror = () => {
    onError('WebSocket error — live prices unavailable');
  };

  return () => {
    ws.close();
  };
}
