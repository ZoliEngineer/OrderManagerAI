/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const MARKET_DATA = 'http://localhost:8080';
const ACCOUNT_SVC = 'http://localhost:8081';
const ORDER_SVC = 'http://localhost:8082';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Dev-only reverse proxy, so the browser talks to one origin and no CORS setup is needed.
    // More specific prefixes first: Vite matches proxy keys in insertion order.
    proxy: {
      '/api/accounts': { target: ACCOUNT_SVC, changeOrigin: true },
      '/api/orders': { target: ORDER_SVC, changeOrigin: true },
      '/api': { target: MARKET_DATA, changeOrigin: true },
      '/ws': { target: MARKET_DATA, changeOrigin: true, ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
});
