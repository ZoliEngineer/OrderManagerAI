export function connectMarketDataStream(onMessage, onError) {
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsBase = process.env.REACT_APP_WS_BASE || `${wsProtocol}//${window.location.host}`;
  const ws = new WebSocket(`${wsBase}/ws/marketdata`);

  ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  ws.onerror = () => onError('WebSocket error — live prices unavailable');

  return () => ws.close();
}
