import { act, render, type RenderResult } from '@testing-library/react';
import type { ReactNode } from 'react';

/**
 * `render` for trees that suspend on `use()`. React 19 only retries a suspended render that started
 * inside an *awaited* act scope; plain `render()` is a synchronous act, so the Suspense fallback would
 * never be replaced.
 */
export async function renderAsync(ui: ReactNode): Promise<RenderResult> {
  let result: RenderResult | undefined;
  await act(async () => {
    result = render(ui);
    await Promise.resolve();
  });
  if (!result) throw new Error('render did not run');
  return result;
}
