CREATE TABLE wallets (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);
