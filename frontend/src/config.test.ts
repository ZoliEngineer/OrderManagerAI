import { expect, test } from 'vitest';
import { mergeConfig, type RuntimeConfig } from './config';

const base: RuntimeConfig = {
  apiUrl: 'http://base',
  accountsApiUrl: '',
  ordersApiUrl: '',
  wsBase: '',
  oidc: { authority: 'https://idp', clientId: 'client', scope: 'api', logoutUrl: '' },
};

test('config.json overrides are merged one level deep', () => {
  const merged = mergeConfig(base, { apiUrl: 'http://runtime', oidc: { clientId: 'other' } });

  expect(merged.apiUrl).toBe('http://runtime');
  expect(merged.oidc).toEqual({ ...base.oidc, clientId: 'other' });
  expect(base.oidc.clientId).toBe('client'); // inputs are not mutated
});
