-- V5: Permissionless LP support
-- Replace lp_pubkey_hex (single LP key) with lp_group_id_hex + initial_lp_supply (on-chain LP tokens)

ALTER TABLE pools ADD COLUMN IF NOT EXISTS lp_group_id_hex VARCHAR(64);
ALTER TABLE pools ADD COLUMN IF NOT EXISTS initial_lp_supply BIGINT DEFAULT 10000;

-- For fresh deployments, lp_pubkey_hex is no longer needed
-- Keep the column for backward compat but it's unused by V3 contract
ALTER TABLE pools ALTER COLUMN lp_pubkey_hex DROP NOT NULL;
