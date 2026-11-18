// Registers jest-dom matchers (toBeInTheDocument, ...) with Vitest, including their type declarations.
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});
