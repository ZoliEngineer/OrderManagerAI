export function connectMarketDataStream(onMessage, onError, token) {
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsBase = (process.env.REACT_APP_WS_BASE || `${wsProtocol}//${window.location.host}`)
    .replace(/^wss?:/, wsProtocol);
  const ws = new WebSocket(`${wsBase}/ws/marketdata?access_token=${token}`);

  ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  ws.onerror = () => onError('WebSocket error — live prices unavailable');

  return () => ws.close();
}
