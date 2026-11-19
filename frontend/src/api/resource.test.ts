import { expect, test, vi } from 'vitest';
import { cached, invalidate, retryFailed } from './resource';

test('reuses the promise for a key until it is invalidated', async () => {
  const load = vi.fn(() => Promise.resolve(42));

  const first = cached('answer', load);
  expect(cached('answer', load)).toBe(first);
  await expect(first).resolves.toBe(42);
  expect(load).toHaveBeenCalledTimes(1);

  invalidate('answer');
  expect(cached('answer', load)).not.toBe(first);
  expect(load).toHaveBeenCalledTimes(2);
});

test('keeps a rejected promise until retryFailed, so use() does not retry in a loop', async () => {
  const load = vi.fn().mockRejectedValueOnce(new Error('down')).mockResolvedValueOnce('up');

  const failed = cached('status', load);
  await expect(failed).rejects.toThrow('down');
  expect(cached('status', load)).toBe(failed);

  retryFailed();
  await expect(cached('status', load)).resolves.toBe('up');
  expect(load).toHaveBeenCalledTimes(2);
});
