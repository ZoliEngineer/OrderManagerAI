CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE orders (
    id                UUID           NOT NULL DEFAULT gen_random_uuid(),
    account_id        UUID           NOT NULL,
    user_id           VARCHAR(255)   NOT NULL,
    ticker            VARCHAR(10)    NOT NULL,
    side              VARCHAR(10)    NOT NULL,
    type              VARCHAR(10)    NOT NULL,
    quantity          NUMERIC(19,4)  NOT NULL,
    limit_price       NUMERIC(19,4),
    filled_quantity   NUMERIC(19,4)  NOT NULL DEFAULT 0,
    avg_fill_price    NUMERIC(19,4),
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    rejection_reason  TEXT,
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT pk_orders              PRIMARY KEY (id),
    CONSTRAINT ck_side                CHECK (side IN ('BUY','SELL')),
    CONSTRAINT ck_type                CHECK (type IN ('MARKET','LIMIT')),
    CONSTRAINT ck_status              CHECK (status IN ('NEW','PENDING','PARTIALLY_FILLED','FILLED','CANCELLED','REJECTED')),
    CONSTRAINT ck_quantity_positive   CHECK (quantity > 0),
    CONSTRAINT ck_filled_lte_qty      CHECK (filled_quantity <= quantity),
    CONSTRAINT ck_limit_price_req     CHECK (type != 'LIMIT' OR limit_price IS NOT NULL),
    CONSTRAINT ck_limit_price_pos     CHECK (limit_price IS NULL OR limit_price > 0)
);

CREATE INDEX idx_orders_account_user ON orders (account_id, user_id);
CREATE INDEX idx_orders_status       ON orders (status) WHERE status = 'PENDING';
CREATE INDEX idx_orders_created      ON orders (created_at DESC);
