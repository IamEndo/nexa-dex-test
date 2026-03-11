-- Deposit-then-swap: users deposit NEX/tokens before swap execution
CREATE TABLE deposits (
    deposit_id          SERIAL PRIMARY KEY,
    pool_id             INT NOT NULL REFERENCES pools(pool_id),
    direction           VARCHAR(4) NOT NULL,
    deposit_address     VARCHAR(255) NOT NULL,
    address_index       INT NOT NULL,
    amount_expected     BIGINT NOT NULL,
    amount_received     BIGINT,
    destination_address VARCHAR(255) NOT NULL,
    max_slippage_bps    INT DEFAULT 100,
    status              VARCHAR(20) DEFAULT 'AWAITING_DEPOSIT',
    utxo_tx_id          VARCHAR(64),
    utxo_output_index   INT,
    swap_tx_id          VARCHAR(64),
    trade_id            INT REFERENCES trades(trade_id),
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_deposits_status ON deposits(status);
CREATE INDEX idx_deposits_address ON deposits(deposit_address);
CREATE INDEX idx_deposits_pool ON deposits(pool_id);
CREATE INDEX idx_deposits_expires ON deposits(expires_at);

-- Track next available deposit address index (per direction)
-- Start at 100 to avoid collicting with LP wallet's regular addresses
CREATE TABLE deposit_address_counter (
    direction   VARCHAR(4) NOT NULL,
    next_index  INT NOT NULL DEFAULT 100,
    PRIMARY KEY (direction)
);

INSERT INTO deposit_address_counter (direction, next_index) VALUES ('BUY', 100), ('SELL', 100);
