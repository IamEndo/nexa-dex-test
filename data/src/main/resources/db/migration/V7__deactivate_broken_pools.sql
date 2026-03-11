-- V7: Deactivate pool 5 (broken LP reserve — only 1000 LP tokens, can't addLiquidity)
-- Pool 6 replaces it with proper LP reserve allocation (1B supply, 998M reserve)
UPDATE pools SET status = 'DRAINED' WHERE pool_id = 5;
-- Also deactivate pool 4 (KIBL) whose UTXO is stale/spent
UPDATE pools SET status = 'DRAINED' WHERE pool_id = 4;
