ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;

-- FORCE applies the policy even when connecting as the table owner.
-- Without this, the owner bypasses RLS silently.
ALTER TABLE accounts FORCE ROW LEVEL SECURITY;

-- current_setting(..., true) returns NULL (not an error) when the variable is not set,
-- so unauthenticated connections see zero rows rather than throwing.
CREATE POLICY accounts_user_isolation ON accounts
    AS PERMISSIVE
    FOR ALL
    USING (user_id = current_setting('app.current_user_id', true));
