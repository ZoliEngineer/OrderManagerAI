import useAccounts from '../hooks/useAccounts';

export default function AccountSelector({ selectedId, onSelect }) {
  const { accounts, loading, error } = useAccounts();

  if (error) return null;

  return (
    <div className="account-selector">
      <span className="account-selector-label">Account</span>
      <select
        className="account-select"
        value={selectedId}
        onChange={(e) => onSelect(e.target.value)}
        disabled={loading}
      >
        <option value="" disabled>
          {loading ? 'Loading…' : 'Select account'}
        </option>
        {accounts.map((account) => (
          <option key={account.id} value={account.id}>
            {account.displayName}
          </option>
        ))}
      </select>
    </div>
  );
}
