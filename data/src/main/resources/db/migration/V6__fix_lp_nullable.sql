-- V6: Fix lp_group_id_hex nullable for pre-V5 pools
UPDATE pools SET lp_group_id_hex = '' WHERE lp_group_id_hex IS NULL;
ALTER TABLE pools ALTER COLUMN lp_group_id_hex SET DEFAULT '';
ALTER TABLE pools ALTER COLUMN lp_group_id_hex SET NOT NULL;
