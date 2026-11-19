import { use, type ChangeEvent } from 'react';
import { accountsResource } from '../api/accountApi';
import { toAccountId, type AccountId, type AccountSummary } from '../types/account';
import { DataBoundary } from './ErrorBoundary';

interface AccountSelectorProps {
  readonly selectedId: AccountId | null;
  readonly onSelect: (id: AccountId) => void;
}

interface AccountSelectProps extends AccountSelectorProps {
  /** `null` while the accounts are still loading. */
  readonly accounts: readonly AccountSummary[] | null;
}

function AccountSelect({ accounts, selectedId, onSelect }: AccountSelectProps) {
  function handleChange(event: ChangeEvent<HTMLSelectElement>): void {
    onSelect(toAccountId(event.target.value));
  }

  return (
    <div className="account-selector">
      <label className="account-selector-label" htmlFor="account-select">
        Account
      </label>
      <select
        id="account-select"
        className="account-select"
        value={selectedId ?? ''}
        onChange={handleChange}
        disabled={accounts === null}
      >
        <option value="" disabled>
          {accounts === null ? 'Loading…' : 'Select account'}
        </option>
        {accounts?.map((account) => (
          <option key={account.id} value={account.id}>
            {account.displayName}
          </option>
        ))}
      </select>
    </div>
  );
}

function LoadedAccountSelect(props: AccountSelectorProps) {
  // `use()` suspends until the accounts arrive; the Suspense fallback is the same select, disabled.
  const accounts = use(accountsResource());
  return <AccountSelect {...props} accounts={accounts} />;
}

export function AccountSelector(props: AccountSelectorProps) {
  return (
    // Its own boundary: a slow or failed account list must not blank the rest of the top bar.
    <DataBoundary loading={<AccountSelect {...props} accounts={null} />} error={() => null}>
      <LoadedAccountSelect {...props} />
    </DataBoundary>
  );
}
