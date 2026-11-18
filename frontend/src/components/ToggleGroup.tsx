export interface ToggleOption<T extends string> {
  readonly value: T;
  readonly label: string;
  readonly className?: string;
}

interface ToggleGroupProps<T extends string> {
  readonly options: readonly ToggleOption<T>[];
  readonly value: T;
  readonly onChange: (value: T) => void;
}

/**
 * A generic component: `T` is inferred from `options`, so `onChange` receives the same literal
 * union (e.g. `'BUY' | 'SELL'`), not a plain string.
 */
export function ToggleGroup<T extends string>({ options, value, onChange }: ToggleGroupProps<T>) {
  return (
    <div className="toggle-group">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          className={['toggle-btn', option.className, option.value === value && 'active'].filter(Boolean).join(' ')}
          onClick={() => {
            onChange(option.value);
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
