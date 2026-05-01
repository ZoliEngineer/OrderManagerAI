import request from './client';

export const getMarketData = () => request('/api/marketdata');
export const getMarketStatus = () => request('/api/market-status');
