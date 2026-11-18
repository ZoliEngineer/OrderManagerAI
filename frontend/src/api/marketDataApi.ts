import { getConfig } from '../config';
import type { MarketStatus, Stock } from '../types/marketData';
import { request } from './client';

const marketData = <T>(path: string): Promise<T> => request<T>(getConfig().apiUrl, path);

export const getMarketData = (): Promise<Stock[]> => marketData('/api/marketdata');

export const getMarketStatus = (): Promise<MarketStatus> => marketData('/api/market-status');
