# Wallet Service

A prepaid wallet service for a logistics platform. The core mechanics are pretty straightforward: customers load funds into their wallet, and every time they place an order, we deduct exactly ₹100. 
Rules:
    - Wallet Balance Never drops below zero.
    - Deduction amt. is always fixed at ₹100

---

## Prerequisites

- **Java 21+** — to run the application
- **Docker 24+** — for Postgres and the full-stack compose setup (Docker Desktop on Mac/Windows includes Compose v2; on Linux install it separately)
- **Make** — for the convenience commands
- **uuidgen** — used in the stub script to generate idempotency keys (pre-installed on macOS; on Ubuntu: `apt install uuid-runtime`)
- **python3** — used in the stub script for the ledger pretty-print (pre-installed on macOS and most Linux distros; the stub falls back to raw JSON if unavailable)


## Getting Started

I've set up some make commands to keep local development easy:

```bash
# 1. Start everything (Postgres + Wallet Service) in Docker
make up

# 2. Run the Order Service stub - demonstrates the full integration
make stub

# 3. Run all tests
make test
```

For local dev (app in IDE, only Postgres in Docker):
```bash
make db-up   # start Postgres
make run     # run Spring Boot app natively
```

---

## API

I set up Swagger integration. So visiting `{server-url}/swagger-ui.html` will show up the list of APIs.

| Method | Endpoint | Who calls it | Notes |
|--------|----------|--------------|-------|
| `POST` | `/wallets` | Test / setup | Creates a new wallet |
| `POST` | `/wallets/:id/topup` | Customer (frontend) | Body: `{"amount": 500}`. Requires `Idempotency-Key` header |
| `POST` | `/wallets/:id/deduct` | Order Service | Requires `Idempotency-Key` header |
| `GET` | `/wallets/:id/balance` | Anyone | Returns derived balance |
| `GET` | `/wallets/:id/transactions` | Anyone | Full ledger, newest first |



---

## Architecture & Key Decisions

### 1. The balance is never stored - it is always derived

There is no `balance` column in the `wallets` table. Balance is computed on every read as:

```sql
SELECT COALESCE(SUM(
    CASE type WHEN 'TOPUP' THEN amount ELSE -amount END
), 0)
FROM transactions WHERE wallet_id = ?
```

**Why:** Storing a balance means it can drift from the ledger if a bug partially applies a transaction. Deriving it from the ledger means correctness is structural - the ledger *is* the truth and there is nothing to drift. The trade-off is a slightly heavier read, which is acceptable at this scale and easily solved with a `cached_balance` column if needed later.

### 2. Idempotency is a database constraint, not application logic — and it applies to both endpoints

Both `/topup` and `/deduct` require an `Idempotency-Key` header. The failure modes are symmetric: a customer clicking "Add ₹500" on a slow network has exactly the same retry problem as the Order Service retrying `/deduct` after a timeout. Crediting a wallet twice is at least as bad as a double deduction — you give away money rather than just blocking an order.

The mechanism is identical for both: the `transactions` table has a `UNIQUE` constraint on `idempotency_key`. On each request, the service does a fast-path lookup first. If the key already exists, the original transaction is returned immediately without touching the wallet. If two identical requests arrive concurrently and both pass the fast path, the DB constraint is the final safety net — one insert wins, the other throws `DataIntegrityViolationException`, and the service catches that and returns the winning row.

The `idempotency_key` column is nullable at the DB level (no migration needed to enforce this for existing rows) and required at the application layer for all new transactions.

**Why:** DB constraints are guarantees that survive server crashes, restarts, and concurrent retries across any number of app instances.

### 3. Concurrency is handled in the DB with `SELECT FOR UPDATE`

The deduct flow runs inside a `@Transactional` method. The wallet row is locked with `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) before reading the balance. This forces concurrent deductions on the same wallet to serialize at the DB level.

**Why not application-level locking:** Mutexes don't survive across JVM instances. `FOR UPDATE` works regardless of how many app instances are running.

**Isolation level:** `READ_COMMITTED` is sufficient. The `FOR UPDATE` lock guarantees that the second transaction sees the committed balance after the first commits - no higher isolation needed.

### 4. `amount` is always positive; direction comes from `type`

A `TOPUP` of ₹500 and a `DEDUCTION` of ₹100 both store a positive `amount`. The `type` enum (`TOPUP`/`DEDUCTION`) captures direction.

**Why:** Storing signed amounts creates ambiguity (is `-100` a deduction or a reversed topup?). Always-positive amounts with an explicit type column are unambiguous and self-documenting in the ledger.

### 5. Deduction amount is fixed server-side at ₹100

The `/deduct` endpoint does not accept an amount in the request body. The ₹100 figure is a business constant defined once in `WalletService.DEDUCTION_AMOUNT`.

**Why:** The Order Service should not be able to deduct arbitrary amounts. Business rules belong in the service, not in API inputs.

---

## Data Model

```
wallets
───────────────────────────────
id            UUID    PK
created_at    TIMESTAMP

transactions
───────────────────────────────
id                UUID    PK
wallet_id         UUID    FK → wallets(id)
type              ENUM    ('TOPUP', 'DEDUCTION')
amount            NUMERIC(12,2)   CHECK (amount > 0)
idempotency_key   TEXT    UNIQUE (nullable at DB level; required by application for all rows)
created_at        TIMESTAMP
```

Indexes:
- `idx_transactions_wallet_id` - balance computation and ledger queries
- `idx_transactions_idempotency_key` (partial, where not null) - idempotency lookups

---

## Testing Strategy

The suite has three layers, each with a distinct responsibility.

**Layer 1 - Unit tests (`WalletServiceTest`):** Both repositories are mocked; the
service layer runs in complete isolation with no Spring context and no database.
The goal is exhaustive coverage of business logic branches: the balance check
boundary (exactly ₹100, one rupee short, zero), the idempotency fast path, and
the race condition path where the DB constraint fires and the service falls back
to fetching the existing transaction. These edge cases are hard to trigger
reliably against a real database but trivial to set up deterministically with
mocks.

**Layer 2 - Controller tests (`WalletControllerTest`):** `@WebMvcTest` with a
mocked service. The service is irrelevant here - these tests verify the HTTP
contract: status codes, request validation, error response shape, and required
headers. A missing `Idempotency-Key` must return 400, not 500. Insufficient
balance must return 402, not 400. These are contracts the Order Service depends
on and must not silently break.

**Layer 3 - Integration tests (`WalletServiceIntegrationTest`):** Real Postgres
via Testcontainers. This is where the concurrency guarantees are actually proven as mocks cannot verify them. The critical test spins up 10 threads simultaneously
attempting to deduct from a wallet with exactly ₹100. Without `SELECT FOR UPDATE`
several could possibly succeed and the balance would go to negative. With it, exactly 1
succeeds and the rest receive `InsufficientBalanceException`. A second concurrency
test sends the same idempotency key from 5 threads simultaneously - the wallet
must be debited exactly once regardless of which thread wins the insert race.
Topup idempotency is also integration-tested: replaying the same topup key must
credit the wallet exactly once, and replaying with a different amount must return
the original transaction (amount from the first call wins).



---

## What I Would Add With More Time

- Balance Caching: Add a `cached_balance` column that updates atomically alongside transactions to give us O(1) reads. Balance Computation During Deduct causes O(n) problem as it calls `computeBalance` to check if there's sufficient funds, which scans all rows before deciding whether to proceed

- Cursor Pagination: Add a cursor-based pagination on the /transactions endpoint as a Large transaction history would make the reads very slow.
Cursor pagination is O(1) per page regardless of depth..

- Account Freezes: Add a status enum (ACTIVE, FROZEN) to the wallets table so our fraud team can lock down accounts and block deductions.

- Audit Logging: Spin up an `audit_log` table to track who triggered what (customer IDs, Order Service request IDs) for compliance.

- Outbox Pattern: Publish a `WalletDebited` event whenever money moves, so analytics or notification services can react without being tightly coupled to our core logic.

- Observability: Although Actuator is already setup, I would hook up Micrometer to track deduction failure rates, endpoint latencies (p99), hikaricp active/pending connections etc.

- Distributed Locking: At massive scale, I'd bring in Redis for advisory locks just as a backup layer to our FOR UPDATE queries, specifically to handle database failover hiccups although it would increase operational complexity.


---

## AI Tool Usage

This service was built using Cursor and Claude as a pair programmers throughout. AI was used for: scaffolding the layered architecture and having it critiqued for correctness,
reasoning through the `SELECT FOR UPDATE` vs optimistic locking trade-off,
and validating that `READ_COMMITTED` isolation is sufficient given the locking
strategy. All architectural decisions - ledger-derived balance, DB-layer
idempotency, pessimistic locking - were reasoned through deliberately rather than
accepted as boilerplate output.

---

