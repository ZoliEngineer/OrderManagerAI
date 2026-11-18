import type { ChangeEvent } from 'react';
import { useAccounts } from '../hooks/useAccounts';
import { toAccountId, type AccountId } from '../types/account';

interface AccountSelectorProps {
  readonly selectedId: AccountId | null;
  readonly onSelect: (id: AccountId) => void;
}

export function AccountSelector({ selectedId, onSelect }: AccountSelectorProps) {
  const accounts = useAccounts();
  if (accounts.status === 'error') return null;

  const loading = accounts.status !== 'success';

  function handleChange(event: ChangeEvent<HTMLSelectElement>): void {
    onSelect(toAccountId(event.target.value));
  }

  return (
    <div className="account-selector">
      <span className="account-selector-label">Account</span>
      <select className="account-select" value={selectedId ?? ''} onChange={handleChange} disabled={loading}>
        <option value="" disabled>
          {loading ? 'Loading…' : 'Select account'}
        </option>
        {accounts.status === 'success' &&
          accounts.data.map((account) => (
            <option key={account.id} value={account.id}>
              {account.displayName}
            </option>
          ))}
      </select>
    </div>
  );
}
