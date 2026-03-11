-- V3: Add V2 permissionless pool tracking columns
ALTER TABLE pools ADD COLUMN IF NOT EXISTS pool_utxo_txid VARCHAR(64);
ALTER TABLE pools ADD COLUMN IF NOT EXISTS pool_utxo_vout INTEGER;
ALTER TABLE pools ADD COLUMN IF NOT EXISTS contract_version VARCHAR(10) DEFAULT 'v1';
