-- V8: Fix pool status (V7 used invalid 'DEAD', should be 'DRAINED')
UPDATE pools SET status = 'DRAINED' WHERE pool_id IN (4, 5);
