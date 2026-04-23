import request from './client';

export const getMarketData = () => request('/api/marketdata');
