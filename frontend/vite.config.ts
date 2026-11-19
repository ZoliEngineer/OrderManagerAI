/// <reference types="vitest/config" />
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';
import babel from '@rolldown/plugin-babel';
import react, { reactCompilerPreset } from '@vitejs/plugin-react';

const MARKET_DATA = 'http://localhost:8080';
const ACCOUNT_SVC = 'http://localhost:8081';
const ORDER_SVC = 'http://localhost:8082';

/**
 * Dev-only reverse proxy, so the browser talks to one origin and no CORS setup is needed.
 *
 * - Default: the three local services.
 * - `DEV_PROXY_TARGET=https://<id>.cloudfront.net`: the deployed environment. CloudFront routes every
 *   prefix to the right service (the ALB itself only accepts CloudFront). `Origin` is rewritten to the
 *   target, so to the backend this looks like the deployed SPA's same-origin traffic and its CORS
 *   allow-list (which does not contain localhost) does not apply.
 */
function proxyConfig(remote: string | undefined): Record<string, ProxyOptions> {
  if (!remote) {
    // More specific prefixes first: Vite matches proxy keys in insertion order.
    return {
      '/api/accounts': { target: ACCOUNT_SVC, changeOrigin: true },
      '/api/orders': { target: ORDER_SVC, changeOrigin: true },
      '/api': { target: MARKET_DATA, changeOrigin: true },
      '/ws': { target: MARKET_DATA, changeOrigin: true, ws: true },
    };
  }

  const origin = new URL(remote).origin;
  const toRemote: ProxyOptions = {
    target: origin,
    changeOrigin: true,
    secure: true,
    configure: (proxy) => {
      proxy.on('proxyReq', (req) => {
        req.setHeader('origin', origin);
      });
      proxy.on('proxyReqWs', (req) => {
        req.setHeader('origin', origin);
      });
    },
  };
  return { '/api': toRemote, '/ws': { ...toRemote, ws: true } };
}

export default defineConfig(({ mode }) => {
  // '' prefix: also read non-VITE_ variables, e.g. from .env.development.local, without exposing them to the app.
  const env = loadEnv(mode, import.meta.dirname, '');

  return {
    // React Compiler (stable Babel route): memoizes components and hooks at build time,
    // so the code needs no hand-written useMemo / useCallback / memo.
    plugins: [react(), babel({ presets: [reactCompilerPreset()] })],
    server: {
      port: 3000,
      proxy: proxyConfig(env.DEV_PROXY_TARGET),
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      restoreMocks: true,
    },
  };
});
