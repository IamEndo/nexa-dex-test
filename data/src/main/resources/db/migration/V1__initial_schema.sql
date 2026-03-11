-- NexaDEX initial schema

CREATE TABLE tokens (
    group_id_hex    VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255),
    ticker          VARCHAR(10),
    decimals        INT DEFAULT 0,
    document_url    TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE pools (
    pool_id             SERIAL PRIMARY KEY,
    token_group_id_hex  VARCHAR(64) NOT NULL REFERENCES tokens(group_id_hex),
    lp_pubkey_hex       VARCHAR(66) NOT NULL,
    contract_address    VARCHAR(255) NOT NULL,
    contract_blob       BYTEA NOT NULL,
    status              VARCHAR(20) DEFAULT 'DEPLOYING',
    nex_reserve         BIGINT NOT NULL DEFAULT 0,
    token_reserve       BIGINT NOT NULL DEFAULT 0,
    deploy_tx_id        VARCHAR(64),
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_pools_token ON pools(token_group_id_hex);
CREATE INDEX idx_pools_status ON pools(status);

CREATE TABLE trades (
    trade_id        SERIAL PRIMARY KEY,
    pool_id         INT NOT NULL REFERENCES pools(pool_id),
    direction       VARCHAR(4) NOT NULL,
    amount_in       BIGINT NOT NULL,
    amount_out      BIGINT NOT NULL,
    price           DOUBLE PRECISION,
    nex_reserve_after   BIGINT NOT NULL,
    token_reserve_after BIGINT NOT NULL,
    tx_id           VARCHAR(64),
    trader_address  VARCHAR(255),
    status          VARCHAR(10) DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trades_pool ON trades(pool_id);
CREATE INDEX idx_trades_status ON trades(status);
CREATE INDEX idx_trades_created ON trades(created_at);
CREATE INDEX idx_trades_pool_created ON trades(pool_id, created_at);

CREATE TABLE lp_shares (
    share_id        SERIAL PRIMARY KEY,
    pool_id         INT NOT NULL REFERENCES pools(pool_id),
    provider_addr   VARCHAR(255) NOT NULL,
    nex_contributed BIGINT NOT NULL,
    tokens_contributed BIGINT NOT NULL,
    share_pct       DOUBLE PRECISION,
    deposit_tx_id   VARCHAR(64),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_lp_shares_pool ON lp_shares(pool_id);

CREATE TABLE ohlcv (
    pool_id     INT NOT NULL REFERENCES pools(pool_id),
    interval    VARCHAR(5) NOT NULL,
    open_time   TIMESTAMPTZ NOT NULL,
    open        DOUBLE PRECISION,
    high        DOUBLE PRECISION,
    low         DOUBLE PRECISION,
    close       DOUBLE PRECISION,
    volume_nex  BIGINT DEFAULT 0,
    volume_token BIGINT DEFAULT 0,
    trade_count INT DEFAULT 0,
    PRIMARY KEY (pool_id, interval, open_time)
);

CREATE INDEX idx_ohlcv_lookup ON ohlcv(pool_id, interval, open_time DESC);
