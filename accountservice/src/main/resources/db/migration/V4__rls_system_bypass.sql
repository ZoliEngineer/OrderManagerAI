-- Allow internal system operations (e.g. cache warming) to read all rows by
-- setting app.current_user_id = '__system__' within the transaction.
-- All normal user-scoped queries still require a matching user_id.
DROP POLICY accounts_user_isolation ON accounts;

CREATE POLICY accounts_user_isolation ON accounts
    AS PERMISSIVE
    FOR ALL
    USING (user_id = current_setting('app.current_user_id', true)
           OR current_setting('app.current_user_id', true) = '__system__');
