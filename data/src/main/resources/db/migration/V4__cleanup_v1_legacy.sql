-- V4: Remove V1 legacy data and unused tables
-- Only delete pools with contract_version 'v1' that are no longer functional.
-- Deposit tables from V2 migration are unused (deposit flow removed).

-- 1. Delete dependent data for V1 pools only
DELETE FROM ohlcv WHERE pool_id IN (SELECT pool_id FROM pools WHERE contract_version = 'v1');
DELETE FROM lp_shares WHERE pool_id IN (SELECT pool_id FROM pools WHERE contract_version = 'v1');
DELETE FROM trades WHERE pool_id IN (SELECT pool_id FROM pools WHERE contract_version = 'v1');

-- 2. Delete the V1 pools themselves
DELETE FROM pools WHERE contract_version = 'v1';

-- 3. Delete orphaned tokens (no remaining pool references)
DELETE FROM tokens WHERE group_id_hex NOT IN (
    SELECT DISTINCT token_group_id_hex FROM pools
);

-- 4. Drop unused deposit tables (V1 deposit flow removed)
DROP TABLE IF EXISTS deposits;
DROP TABLE IF EXISTS deposit_address_counter;

-- 5. Change contract_version default from 'v1' to 'v2'
ALTER TABLE pools ALTER COLUMN contract_version SET DEFAULT 'v2';
