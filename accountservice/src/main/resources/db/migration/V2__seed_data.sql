-- Replace ADVISOR_SUB_1 with the real Entra ID `sub` claim of your test advisor account.
-- To find it: log in, call GET /api/accounts (or decode your JWT at jwt.ms), copy the `sub` field.
-- Store it in .env as ADVISOR_SUB_1=<value> and re-run this migration (or update the rows manually).

INSERT INTO accounts (user_id, display_name, cash_balance, reserved_balance) VALUES
  ('29NG0fX-kqHRPFxriFWHvEcoOvX5R6uAhYuAKcLuMrw', 'John Smith — Trading',    250000.0000, 0),
  ('29NG0fX-kqHRPFxriFWHvEcoOvX5R6uAhYuAKcLuMrw', 'Jane Doe — Retirement',   180000.0000, 0),
  ('29NG0fX-kqHRPFxriFWHvEcoOvX5R6uAhYuAKcLuMrw', 'Alice Brown — Trading',    95000.0000, 0);
