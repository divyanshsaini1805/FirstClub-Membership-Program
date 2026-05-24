#!/usr/bin/env bash
# Builds Docker images, starts all services, exercises every API endpoint,
# then runs the full test suite.
#
# Prerequisites: Docker Desktop, Java 17+, Maven 3.9+
#
# Usage:
#   ./run.sh                      build + start + api demo + all tests
#   ./run.sh --skip-tests         build + start + api demo only
#   PORT=8090 ./run.sh            use a different port
#   PAUSE=0 ./run.sh              no sleep between steps (fast scroll)
#   PAUSE=5 ./run.sh              longer pause between steps

set -euo pipefail

PORT=${PORT:-8080}
PAUSE=${PAUSE:-3}
SKIP_TESTS=false

for arg in "$@"; do
  [[ "$arg" == "--skip-tests" ]] && SKIP_TESTS=true
done

# ── prerequisites ─────────────────────────────────────────────────────────────
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: Docker is not running. Start Docker Desktop and try again."
  exit 1
fi

if [[ "$SKIP_TESTS" == "false" ]] && ! command -v mvn > /dev/null 2>&1; then
  echo "ERROR: mvn not found. Install Java 17 + Maven 3.9+, or pass --skip-tests."
  exit 1
fi

# ── build + start ─────────────────────────────────────────────────────────────
echo "Building images and starting Postgres, Redis, and app on port $PORT ..."
APP_PORT=$PORT docker compose up --build -d

# ── wait for health ───────────────────────────────────────────────────────────
echo "Waiting for the app to be healthy (up to 120 s) ..."
for i in $(seq 1 24); do
  if curl -sf "http://localhost:$PORT/actuator/health" > /dev/null 2>&1; then
    echo "App is up."
    break
  fi
  if [[ $i -eq 24 ]]; then
    echo "ERROR: App did not start in time. Check logs with: docker compose logs app"
    exit 1
  fi
  sleep 5
done

echo ""
echo "  Ping:    http://localhost:$PORT/api/v1/ping"
echo "  Swagger: http://localhost:$PORT/swagger-ui.html"
echo "  Health:  http://localhost:$PORT/actuator/health"
echo ""

# ── API walkthrough helpers ────────────────────────────────────────────────────
BASE_URL="http://localhost:$PORT"
DEMO_EMAIL="run-$(date +%s)@example.com"
CT="Content-Type: application/json"

_banner() {
  printf '\n\033[1;37m%s\033[0m\n' "════════════════════════════════════════════════════════"
  printf '\033[1;37m  %s\033[0m\n' "$1"
  printf '\033[1;37m%s\033[0m\n'   "════════════════════════════════════════════════════════"
}
_step() {
  printf '\n\033[1;34m── Step %s: %s\033[0m\n' "$1" "$2"
}
_info() {
  printf '\033[90m  %s\033[0m\n' "$1"
}
_req() {
  printf '\033[33m  REQUEST  %-8s %s\033[0m\n' "$1" "$2"
}
_body() {
  printf '\033[90m           Body: %s\033[0m\n' "$1"
}
_resp() {
  printf '\033[32m  RESPONSE\033[0m\n'
  echo "$1" | python3 -m json.tool 2>/dev/null | sed 's/^/           /' \
    || printf '           %s\n' "$1"
  sleep "$PAUSE"
}

_banner "API Walkthrough — all 12 endpoints exercised in sequence"

# ── 1. Ping ───────────────────────────────────────────────────────────────────
_step 1 "Ping"
_info "Lightweight smoke-test. Confirms the app is reachable and the server clock is working."
_info "No auth required. Useful as a first check before attempting authenticated calls."
_req GET "$BASE_URL/api/v1/ping"
_resp "$(curl -fsS "$BASE_URL/api/v1/ping")"

# ── 2. Health ─────────────────────────────────────────────────────────────────
_step 2 "Health check"
_info "Spring Actuator health endpoint. Checks app, Postgres connection, and Redis connection."
_info "Returns HTTP 200 with status=UP when all three subsystems are healthy."
_req GET "$BASE_URL/actuator/health"
_resp "$(curl -fsS "$BASE_URL/actuator/health")"

# ── 3. Register ───────────────────────────────────────────────────────────────
_step 3 "Register"
_info "Creates a new user account plus a wallet in the same transaction."
_info "The wallet is immediately credited with the signup bonus (default 50,000)."
_info "Returns a signed JWT — copy the accessToken to use in all authenticated calls."
REG_BODY="{\"email\":\"$DEMO_EMAIL\",\"password\":\"password123\",\"fullName\":\"Demo User\",\"cohort\":\"REGULAR\"}"
_req POST "$BASE_URL/api/v1/auth/register"
_body "$REG_BODY"
REG=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/register" -H "$CT" -d "$REG_BODY")
_resp "$REG"
TOKEN=$(echo "$REG" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
AUTH="Authorization: Bearer $TOKEN"

# ── 4. Login ──────────────────────────────────────────────────────────────────
_step 4 "Login"
_info "Authenticates with email + password, returns a fresh JWT."
_info "The token from register is still valid — login is shown here for completeness."
LOGIN_BODY="{\"email\":\"$DEMO_EMAIL\",\"password\":\"password123\"}"
_req POST "$BASE_URL/api/v1/auth/login"
_body "$LOGIN_BODY"
_resp "$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" -H "$CT" -d "$LOGIN_BODY")"

# ── 5. List plans ─────────────────────────────────────────────────────────────
_step 5 "List plans"
_info "Returns the billing-cadence catalogue: Monthly (30 days / 199), Quarterly (90 days / 499),"
_info "and Yearly (365 days / 1,499). When subscribing you pick one plan + one tier."
_info "The plan controls how long the subscription runs; the tier controls which features you get."
_req GET "$BASE_URL/api/v1/plans"
PLANS=$(curl -fsS "$BASE_URL/api/v1/plans")
_resp "$PLANS"
PLAN_ID=$(echo "$PLANS" | python3 -c \
  'import sys,json; d=json.load(sys.stdin); print(next(p["id"] for p in d if p["code"]=="MONTHLY"))')

# ── 6. List tiers ─────────────────────────────────────────────────────────────
_step 6 "List tiers"
_info "Returns the feature-level catalogue: Silver (free), Gold (500), Platinum (1,500)."
_info "Each tier has benefit bindings with per-tier JSONB config — e.g. Silver gets 2% discount"
_info "on GROCERY; Platinum gets 10% across GROCERY, ELECTRONICS, and FASHION."
_req GET "$BASE_URL/api/v1/tiers"
TIERS=$(curl -fsS "$BASE_URL/api/v1/tiers")
_resp "$TIERS"
SILVER_ID=$(echo "$TIERS" | python3 -c \
  'import sys,json; d=json.load(sys.stdin); print(next(t["id"] for t in d if t["code"]=="SILVER"))')
GOLD_ID=$(echo "$TIERS" | python3 -c \
  'import sys,json; d=json.load(sys.stdin); print(next(t["id"] for t in d if t["code"]=="GOLD"))')

# ── 7. Wallet balance ─────────────────────────────────────────────────────────
_step 7 "Wallet balance"
_info "Shows current balance. The signup bonus (50,000 by default) should already be credited."
_info "Balance is protected by an @Version optimistic lock — concurrent debits produce a 409"
_info "CONCURRENT_MODIFICATION rather than silently letting a double-charge through."
_req GET "$BASE_URL/api/v1/wallet"
_resp "$(curl -fsS "$BASE_URL/api/v1/wallet" -H "$AUTH")"

# ── 8. Subscribe ──────────────────────────────────────────────────────────────
_step 8 "Subscribe — Monthly plan + Silver tier"
_info "Starts a subscription. The wallet is charged planPrice + tierPrice in a single transaction."
_info "An Idempotency-Key header is sent — if this request is retried, the server returns the"
_info "original response verbatim without charging again. Safe for unreliable networks."
SUB_BODY="{\"planId\":$PLAN_ID,\"tierId\":$SILVER_ID}"
_req POST "$BASE_URL/api/v1/users/me/subscriptions"
_body "$SUB_BODY"
SUB=$(curl -fsS -X POST "$BASE_URL/api/v1/users/me/subscriptions" \
  -H "$AUTH" -H "$CT" \
  -H "Idempotency-Key: run-sub-$(date +%s)" \
  -d "$SUB_BODY")
_resp "$SUB"
SUB_ID=$(echo "$SUB" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# ── 9. Wallet transactions ────────────────────────────────────────────────────
_step 9 "Wallet transactions"
_info "Shows the append-only ledger. Rows are never updated or deleted — every charge and credit"
_info "is a new row. You should see exactly two entries: CREDIT (signup bonus) and DEBIT"
_info "(subscription charge). The idempotency_key column enforces uniqueness at the DB level."
_req GET "$BASE_URL/api/v1/wallet/transactions"
_resp "$(curl -fsS "$BASE_URL/api/v1/wallet/transactions" -H "$AUTH")"

# ── 10. Membership snapshot ───────────────────────────────────────────────────
_step 10 "Membership snapshot"
_info "The combined view of your active subscription. Two tier fields matter:"
_info "  purchasedTier — what you paid for (the source of truth for billing)."
_info "  effectiveTier — what you actually get (may be higher via auto-promotion)."
_info "Right now both are Silver because no promotion has fired yet."
_req GET "$BASE_URL/api/v1/users/me/membership"
_resp "$(curl -fsS "$BASE_URL/api/v1/users/me/membership" -H "$AUTH")"

# ── 11. Upgrade Silver → Gold ─────────────────────────────────────────────────
_step 11 "Upgrade Silver → Gold"
_info "Immediately upgrades to Gold. The wallet is charged the prorated price difference:"
_info "  (goldPrice - silverPrice) × (daysRemaining / planDurationDays)"
_info "The subscription status becomes ACTIVE and effectiveTier is Gold right away."
UP_BODY="{\"targetTierId\":$GOLD_ID}"
_req POST "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID/change-tier"
_body "$UP_BODY"
_resp "$(curl -fsS -X POST "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID/change-tier" \
  -H "$AUTH" -H "$CT" -d "$UP_BODY")"

# ── 12. Downgrade Gold → Silver ───────────────────────────────────────────────
_step 12 "Downgrade Gold → Silver"
_info "Schedules a downgrade for the end of the current billing period. No money movement."
_info "The status becomes PENDING_DOWNGRADE. The user keeps Gold benefits until endsAt."
_info "A ScheduledTierChange row is written; the nightly maintenance job applies it."
DOWN_BODY="{\"targetTierId\":$SILVER_ID}"
_req POST "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID/change-tier"
_body "$DOWN_BODY"
_resp "$(curl -fsS -X POST "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID/change-tier" \
  -H "$AUTH" -H "$CT" -d "$DOWN_BODY")"

# ── 13. Place 5 orders → ORDER_COUNT auto-promotion ──────────────────────────
_step 13 "Place 5 orders of 1,000 each"
_info "Each order fires the eligibility engine after the DB transaction commits."
_info "The ORDER_COUNT rule (5+ orders within 30 days → Gold) evaluates on every order."
_info "After the 5th order a TierPromotion record is written. purchasedTier stays Gold"
_info "(no billing change) — the promotion only lifts effectiveTier."
for i in 1 2 3 4 5; do
  _req POST "$BASE_URL/api/v1/orders  (order $i / 5)"
  _body '{"amount":1000}'
  _resp "$(curl -fsS -X POST "$BASE_URL/api/v1/orders" -H "$AUTH" -H "$CT" -d '{"amount":1000}')"
done
sleep 1

# ── 14. Reevaluate eligibility ────────────────────────────────────────────────
_step 14 "Re-evaluate eligibility"
_info "Manually triggers the promotion rule engine for the current user."
_info "Normally fires automatically after each order via an @TransactionalEventListener."
_info "Useful when you want to force a re-check without placing another order."
_req POST "$BASE_URL/api/v1/users/me/eligibility/reevaluate"
_resp "$(curl -fsS -X POST "$BASE_URL/api/v1/users/me/eligibility/reevaluate" -H "$AUTH")"

# ── 15. Membership — auto-promoted to Gold ────────────────────────────────────
_step 15 "Membership snapshot — after ORDER_COUNT promotion"
_info "effectiveTier should now show Gold from the auto-promotion."
_info "Look for the activePromotion field: it shows the rule that fired and the expiry date."
_info "purchasedTier remains Gold (from the manual upgrade in step 11)."
_req GET "$BASE_URL/api/v1/users/me/membership"
_resp "$(curl -fsS "$BASE_URL/api/v1/users/me/membership" -H "$AUTH")"

# ── 16. Big order → MONTHLY_ORDER_VALUE auto-promotion ───────────────────────
_step 16 "Place one order of 25,000"
_info "Running monthly total is now 5×1,000 + 25,000 = 30,000, which exceeds the"
_info "MONTHLY_ORDER_VALUE rule threshold of 20,000. The Platinum TierPromotion is created."
_info "Again, this is free — purchasedTier (Gold) and billing are unaffected."
_req POST "$BASE_URL/api/v1/orders"
_body '{"amount":25000}'
_resp "$(curl -fsS -X POST "$BASE_URL/api/v1/orders" -H "$AUTH" -H "$CT" -d '{"amount":25000}')"
sleep 1

# ── 17. Membership — auto-promoted to Platinum ────────────────────────────────
_step 17 "Membership snapshot — after MONTHLY_ORDER_VALUE promotion"
_info "effectiveTier should now be Platinum."
_info "activePromotion.reason shows which rule fired (MONTHLY_ORDER_VALUE:rule_id=3)."
_info "activePromotion.validUntil matches endsAt — the promotion expires when the period ends."
_req GET "$BASE_URL/api/v1/users/me/membership"
_resp "$(curl -fsS "$BASE_URL/api/v1/users/me/membership" -H "$AUTH")"

# ── 18. Cancel subscription ───────────────────────────────────────────────────
_step 18 "Cancel subscription"
_info "Cancels the subscription. Status moves to CANCELLED."
_info "Access is retained until endsAt — the user keeps their current effectiveTier"
_info "until the period ends (standard SaaS grace period, no pro-rata refund)."
_req DELETE "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID"
_resp "$(curl -fsS -X DELETE "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID" -H "$AUTH")"

# ── 19. Idempotent cancel replay ──────────────────────────────────────────────
_step 19 "Idempotent cancel replay"
_info "Sends the exact same DELETE again. The server recognises the already-cancelled state"
_info "and returns the same response without any side-effects."
_info "The response header Idempotent-Replay: true confirms this was a replay."
_req DELETE "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID"
_resp "$(curl -fsS -X DELETE "$BASE_URL/api/v1/users/me/subscriptions/$SUB_ID" -H "$AUTH")"

printf '\n\033[1;32m[OK] All API endpoints exercised successfully.\033[0m\n\n'

# ── tests ─────────────────────────────────────────────────────────────────────
if [[ "$SKIP_TESTS" == "false" ]]; then
  echo "Running unit and integration tests (TestContainers spins up its own containers) ..."
  mvn verify
  echo ""
  echo "All tests passed."
fi

echo "Done. Run 'docker compose down' to stop all services."
