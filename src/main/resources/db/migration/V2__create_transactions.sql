CREATE TABLE transactions (
    id                UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id         UUID             NOT NULL REFERENCES wallets(id),
    type              VARCHAR(16)      NOT NULL CHECK (type IN ('TOPUP', 'DEDUCTION')),
    amount            NUMERIC(12, 2)   NOT NULL CHECK (amount > 0),
    idempotency_key   TEXT             UNIQUE,           -- nullable; only set for deductions
    created_at        TIMESTAMP        NOT NULL DEFAULT now()
);

-- Fast balance computation and ledger queries per wallet
CREATE INDEX idx_transactions_wallet_id ON transactions(wallet_id);

-- Idempotency lookups
CREATE UNIQUE INDEX idx_transactions_idempotency_key
    ON transactions(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
