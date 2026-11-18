import { expect, test } from 'vitest';
import { HttpError } from '../api/client';
import { toErrorMessage } from './errors';

test.each<[string, unknown, string]>([
  ['Error', new Error('boom'), 'boom'],
  ['Error subclass', new HttpError(422, 'Insufficient funds'), 'Insufficient funds'],
  ['string', 'plain', 'plain'],
  ['anything else', { code: 1 }, 'Unexpected error'],
])('toErrorMessage: %s', (_name, thrown, expected) => {
  expect(toErrorMessage(thrown)).toBe(expected);
});
