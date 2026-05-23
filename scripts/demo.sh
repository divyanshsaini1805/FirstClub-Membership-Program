#!/usr/bin/env bash
# End-to-end demo of the FirstClub Membership Program.
#
# Walks through: register → subscribe → upgrade → downgrade → orders →
# auto-promotion. Each step prints a labelled summary so a reviewer can
# follow along.
#
# Usage:
#   ./scripts/demo.sh                   # default http://localhost:8080
#   BASE=http://localhost:8090 ./scripts/demo.sh

set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
EMAIL="demo-$(date +%s)@example.com"
PASSWORD="password123"
JSON='Content-Type: application/json'

say()  { printf '\n\033[1;36m── %s ─────────────────────────────────────\033[0m\n' "$1"; }
show() { python3 -c 'import sys,json;print(json.dumps(json.load(sys.stdin),indent=2))'; }
pick() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

say "Register + auto-create wallet"
REG=$(curl -fsS -X POST "$BASE/api/v1/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"fullName\":\"Demo User\",\"cohort\":\"REGULAR\"}")
echo "$REG" | show
TOKEN=$(echo "$REG" | pick "['accessToken']")
H_AUTH="Authorization: Bearer $TOKEN"

say "List plans"
curl -fsS "$BASE/api/v1/plans" | show

say "List tiers"
curl -fsS "$BASE/api/v1/tiers" | show

say "Subscribe — MONTHLY + SILVER (cheap entry to show benefits unlock immediately)"
curl -fsS -X POST "$BASE/api/v1/users/me/subscriptions" -H "$H_AUTH" -H "$JSON" \
  -H "Idempotency-Key: demo-subscribe-$(date +%s)" \
  -d '{"planId":1,"tierId":1}' | show

say "Wallet ledger after subscribe (signup credit + subscription debit)"
curl -fsS "$BASE/api/v1/wallet/transactions" -H "$H_AUTH" | show

say "Upgrade SILVER → GOLD — prorated charge"
curl -fsS -X POST "$BASE/api/v1/users/me/subscriptions/1/change-tier" -H "$H_AUTH" -H "$JSON" \
  -d '{"targetTierId":2}' | show

say "Schedule downgrade GOLD → SILVER (takes effect at period end)"
curl -fsS -X POST "$BASE/api/v1/users/me/subscriptions/1/change-tier" -H "$H_AUTH" -H "$JSON" \
  -d '{"targetTierId":1}' | show

say "Place 5 orders of 1000 each — should unlock auto-promotion to GOLD via ORDER_COUNT rule"
for _ in 1 2 3 4 5; do
  curl -fsS -X POST "$BASE/api/v1/orders" -H "$H_AUTH" -H "$JSON" -d '{"amount":1000}' > /dev/null
done
sleep 1
say "Snapshot — effective tier should reflect the auto-promotion"
curl -fsS "$BASE/api/v1/users/me/membership" -H "$H_AUTH" | show

say "Place one big order of 25000 — total monthly value > 20k → PLATINUM"
curl -fsS -X POST "$BASE/api/v1/orders" -H "$H_AUTH" -H "$JSON" -d '{"amount":25000}' > /dev/null
sleep 1
say "Snapshot — should be effective=PLATINUM"
curl -fsS "$BASE/api/v1/users/me/membership" -H "$H_AUTH" | show

say "Cancel — access retained until period end"
curl -fsS -X DELETE "$BASE/api/v1/users/me/subscriptions/1" -H "$H_AUTH" | show

say "Idempotent replay: re-running the same DELETE returns the same response"
curl -fsS -X DELETE "$BASE/api/v1/users/me/subscriptions/1" -H "$H_AUTH" | show

printf '\n\033[1;32m✓ Demo complete\033[0m\n'
