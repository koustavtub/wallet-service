#!/usr/bin/env bash
# =============================================================================
# Order Service Stub
# Simulates the Order Service calling the Wallet Service to place orders.
#
# Demonstrates:
#   1. Normal order flow (deduction succeeds)
#   2. Exhausted balance (deduction fails with 402)
#   3. Idempotent replay (same order ID sent twice — wallet debited only once)
#
# Usage:
#   chmod +x stub/order_service_stub.sh
#   ./stub/order_service_stub.sh [BASE_URL]      (default: http://localhost:8080)
# =============================================================================

BASE_URL="${1:-http://localhost:8080}"
BOLD="\033[1m"
GREEN="\033[32m"
RED="\033[31m"
YELLOW="\033[33m"
RESET="\033[0m"

separator() { echo -e "\n${BOLD}─────────────────────────────────────────${RESET}"; }
step()      { echo -e "\n${BOLD}▶ $1${RESET}"; }
ok()        { echo -e "  ${GREEN}✓ $1${RESET}"; }
fail()      { echo -e "  ${RED}✗ $1${RESET}"; }
info()      { echo -e "  ${YELLOW}→ $1${RESET}"; }

# ── 1. Create wallet ──────────────────────────────────────────────────────────
separator
step "1. Create a new wallet"
WALLET_RESP=$(curl -s -X POST "$BASE_URL/wallets")
WALLET_ID=$(echo "$WALLET_RESP" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

if [ -z "$WALLET_ID" ]; then
  fail "Failed to create wallet. Is the server running at $BASE_URL?"
  exit 1
fi
ok "Wallet created: $WALLET_ID"

# ── 2. Top up ₹500 ────────────────────────────────────────────────────────────
separator
step "2. Top up ₹500"
TOPUP_RESP=$(curl -s -X POST "$BASE_URL/wallets/$WALLET_ID/topup" \
  -H "Content-Type: application/json" \
  -d '{"amount": 500}')
echo "  Response: $TOPUP_RESP"
ok "Wallet funded"

# ── 3. Place 5 orders (5 × ₹100 = ₹500 total) ────────────────────────────────
separator
step "3. Place 5 orders — each deducts ₹100"
for i in $(seq 1 5); do
  ORDER_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
  RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/wallets/$WALLET_ID/deduct" \
    -H "Idempotency-Key: $ORDER_ID")
  HTTP_CODE=$(echo "$RESP" | tail -1)
  BODY=$(echo "$RESP" | head -1)

  if [ "$HTTP_CODE" = "201" ]; then
    ok "Order $i (key=$ORDER_ID) → HTTP $HTTP_CODE — deducted ₹100"
  else
    fail "Order $i → HTTP $HTTP_CODE — unexpected failure: $BODY"
  fi
done

BALANCE=$(curl -s "$BASE_URL/wallets/$WALLET_ID/balance" | grep -o '"balance":[^,}]*' | cut -d: -f2)
info "Balance after 5 orders: ₹$BALANCE (expected: ₹0)"

# ── 4. 6th order should fail — insufficient balance ───────────────────────────
separator
step "4. Attempt 6th order — expect 402 Insufficient Balance"
ORDER_ID_6=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/wallets/$WALLET_ID/deduct" \
  -H "Idempotency-Key: $ORDER_ID_6")
HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -1)

if [ "$HTTP_CODE" = "402" ]; then
  ok "Got 402 as expected — order rejected"
  info "Response: $BODY"
else
  fail "Expected 402 but got HTTP $HTTP_CODE: $BODY"
fi

# ── 5. Top up ₹200 and replay order 1 ────────────────────────────────────────
separator
step "5. Top up ₹200 then replay order 1 with the SAME idempotency key"
curl -s -X POST "$BASE_URL/wallets/$WALLET_ID/topup" \
  -H "Content-Type: application/json" \
  -d '{"amount": 200}' > /dev/null

# Re-use order 1's key (we need to re-run to get it — simplify by generating a fresh scenario)
REPLAY_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

# First call
FIRST=$(curl -s -X POST "$BASE_URL/wallets/$WALLET_ID/deduct" \
  -H "Idempotency-Key: $REPLAY_KEY")
FIRST_ID=$(echo "$FIRST" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

# Replay — same key
SECOND=$(curl -s -X POST "$BASE_URL/wallets/$WALLET_ID/deduct" \
  -H "Idempotency-Key: $REPLAY_KEY")
SECOND_ID=$(echo "$SECOND" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

if [ "$FIRST_ID" = "$SECOND_ID" ]; then
  ok "Idempotency confirmed — both calls returned transaction ID: $FIRST_ID"
else
  fail "Idempotency BROKEN — first=$FIRST_ID, second=$SECOND_ID"
fi

BALANCE_AFTER=$(curl -s "$BASE_URL/wallets/$WALLET_ID/balance" | grep -o '"balance":[^,}]*' | cut -d: -f2)
info "Balance after replay: ₹$BALANCE_AFTER (expected: ₹100 — deducted only once from ₹200)"

# ── 6. Final ledger ───────────────────────────────────────────────────────────
separator
step "6. Final transaction ledger"
curl -s "$BASE_URL/wallets/$WALLET_ID/transactions" | \
  python3 -c "
import sys, json
txs = json.load(sys.stdin)
print(f'  Total entries: {len(txs)}')
for tx in txs[:8]:
    sign = '+' if tx['type'] == 'TOPUP' else '-'
    print(f\"  {tx['created_at'][:19]}  {sign}₹{tx['amount']:>8}  {tx['type']}\")
" 2>/dev/null || curl -s "$BASE_URL/wallets/$WALLET_ID/transactions"

separator
ok "Stub complete."
