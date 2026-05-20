ALTER TABLE wallets ADD COLUMN cached_balance NUMERIC(12,2) NOT NULL DEFAULT 0;

-- Backfill from existing ledger so existing wallets start with the correct value
UPDATE wallets w
SET cached_balance = (
    SELECT COALESCE(SUM(
        CASE type
            WHEN 'TOPUP'      THEN  amount
            WHEN 'DEDUCTION'  THEN -amount
        END
    ), 0)
    FROM transactions
    WHERE wallet_id = w.id
);
