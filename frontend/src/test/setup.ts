// Registers jest-dom matchers (toBeInTheDocument, ...) with Vitest, including their type declarations.
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { clearCache } from '../api/resource';

// jsdom does not implement <dialog> modality (the DOM types claim it does). Enough of it for the order
// ticket: showModal()/close() toggle `open`.
HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
  this.open = true;
};
HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
  this.open = false;
};

afterEach(() => {
  cleanup();
  // Resources are module-level promises; each test starts without another test's data.
  clearCache();
});
