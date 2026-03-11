-- V9: Remove broken pool #1 (LP reserve at minimum, addLiquidity broken)
-- and renumber pool #2 to pool #1 for a clean public-facing experience.

-- Step 1: Delete all pool #1 data
DELETE FROM ohlcv WHERE pool_id = 1;
DELETE FROM trades WHERE pool_id = 1;
DELETE FROM lp_shares WHERE pool_id = 1;
DELETE FROM pools WHERE pool_id = 1;

-- Step 2: Drop FK constraints to allow pool_id renumbering
ALTER TABLE trades DROP CONSTRAINT IF EXISTS trades_pool_id_fkey;
ALTER TABLE lp_shares DROP CONSTRAINT IF EXISTS lp_shares_pool_id_fkey;
ALTER TABLE ohlcv DROP CONSTRAINT IF EXISTS ohlcv_pool_id_fkey;

-- Step 3: Renumber pool #2 → pool #1
UPDATE pools SET pool_id = 1 WHERE pool_id = 2;
UPDATE trades SET pool_id = 1 WHERE pool_id = 2;
UPDATE lp_shares SET pool_id = 1 WHERE pool_id = 2;
UPDATE ohlcv SET pool_id = 1 WHERE pool_id = 2;

-- Step 4: Re-add FK constraints
ALTER TABLE trades ADD CONSTRAINT trades_pool_id_fkey FOREIGN KEY (pool_id) REFERENCES pools(pool_id);
ALTER TABLE lp_shares ADD CONSTRAINT lp_shares_pool_id_fkey FOREIGN KEY (pool_id) REFERENCES pools(pool_id);
ALTER TABLE ohlcv ADD CONSTRAINT ohlcv_pool_id_fkey FOREIGN KEY (pool_id) REFERENCES pools(pool_id);

-- Step 5: Reset the sequence so next pool created gets id=2
SELECT setval('pools_pool_id_seq', (SELECT COALESCE(MAX(pool_id), 0) FROM pools));
