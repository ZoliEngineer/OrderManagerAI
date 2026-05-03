CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE accounts (
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    user_id          VARCHAR(255)   NOT NULL,
    display_name     VARCHAR(255)   NOT NULL,
    cash_balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reserved_balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_accounts                  PRIMARY KEY (id),
    CONSTRAINT ck_cash_balance_gte_zero     CHECK (cash_balance >= 0),
    CONSTRAINT ck_reserved_balance_gte_zero CHECK (reserved_balance >= 0),
    CONSTRAINT ck_reserved_lte_cash         CHECK (reserved_balance <= cash_balance)
);

CREATE INDEX idx_accounts_user_id ON accounts (user_id);
