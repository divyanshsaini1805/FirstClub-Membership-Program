# Builds Docker images, starts all services, exercises every API endpoint,
# then runs the full test suite.
#
# Prerequisites: Docker Desktop, Java 17+, Maven 3.9+
#
# Usage:
#   .\run.ps1                     build + start + api demo + all tests
#   .\run.ps1 -SkipTests          build + start + api demo only
#   .\run.ps1 -Port 8090          use a different port
#   .\run.ps1 -Pause 0            no sleep between steps (fast scroll)
#   .\run.ps1 -Pause 5            longer pause between steps

param(
    [int]$Port     = 8080,
    [int]$Pause    = 3,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"

# ── prerequisites ─────────────────────────────────────────────────────────────
docker info > $null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Docker is not running. Start Docker Desktop and try again."
    exit 1
}

if (-not $SkipTests -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "mvn not found. Install Java 17 + Maven 3.9+, or run with -SkipTests."
    exit 1
}

# ── build + start ─────────────────────────────────────────────────────────────
Write-Host "Building images and starting Postgres, Redis, and app on port $Port ..."
$env:APP_PORT = $Port
docker compose up --build -d
if ($LASTEXITCODE -ne 0) { exit 1 }

# ── wait for health ───────────────────────────────────────────────────────────
Write-Host "Waiting for the app to be healthy (up to 120 s) ..."
for ($i = 1; $i -le 24; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { break }
    } catch { }
    if ($i -eq 24) {
        Write-Error "App did not start in time. Check logs with: docker compose logs app"
        exit 1
    }
    Start-Sleep 5
}
Write-Host "App is up."
Write-Host ""
Write-Host "  Ping:    http://localhost:$Port/api/v1/ping"
Write-Host "  Swagger: http://localhost:$Port/swagger-ui.html"
Write-Host "  Health:  http://localhost:$Port/actuator/health"
Write-Host ""

# ── API walkthrough helpers ────────────────────────────────────────────────────
$BASE_URL   = "http://localhost:$Port"
$DEMO_EMAIL = "run-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"
$SEP        = "=" * 56

function Banner([string]$title) {
    Write-Host "`n$SEP" -ForegroundColor White
    Write-Host "  $title" -ForegroundColor White
    Write-Host "$SEP" -ForegroundColor White
}

function Step([int]$n, [string]$label) {
    Write-Host "`n-- Step $n : $label" -ForegroundColor Cyan
}

function Info([string]$text) {
    Write-Host "  $text" -ForegroundColor DarkGray
}

function Req([string]$method, [string]$url) {
    Write-Host "  REQUEST  $($method.PadRight(8)) $url" -ForegroundColor Yellow
}

function ReqBody([string]$body) {
    Write-Host "           Body: $body" -ForegroundColor DarkGray
}

function Resp($obj) {
    Write-Host "  RESPONSE" -ForegroundColor Green
    ($obj | ConvertTo-Json -Depth 10) -split "`n" | ForEach-Object {
        Write-Host "           $_"
    }
    if ($Pause -gt 0) { Start-Sleep $Pause }
}

Banner "API Walkthrough - all 12 endpoints exercised in sequence"

# ── 1. Ping ───────────────────────────────────────────────────────────────────
Step 1 "Ping"
Info "Lightweight smoke-test. Confirms the app is reachable and the server clock is working."
Info "No auth required. Useful as a first check before attempting authenticated calls."
Req "GET" "$BASE_URL/api/v1/ping"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/ping")

# ── 2. Health ─────────────────────────────────────────────────────────────────
Step 2 "Health check"
Info "Spring Actuator health endpoint. Checks app, Postgres connection, and Redis connection."
Info "Returns HTTP 200 with status=UP when all three subsystems are healthy."
Req "GET" "$BASE_URL/actuator/health"
Resp (Invoke-RestMethod "$BASE_URL/actuator/health")

# ── 3. Register ───────────────────────────────────────────────────────────────
Step 3 "Register"
Info "Creates a new user account plus a wallet in the same transaction."
Info "The wallet is immediately credited with the signup bonus (default 50,000)."
Info "Returns a signed JWT -- copy the accessToken to use in all authenticated calls."
$regBody = @{ email = $DEMO_EMAIL; password = "password123"; fullName = "Demo User"; cohort = "REGULAR" } | ConvertTo-Json
Req "POST" "$BASE_URL/api/v1/auth/register"
ReqBody $regBody
$reg   = Invoke-RestMethod -Method POST "$BASE_URL/api/v1/auth/register" -ContentType "application/json" -Body $regBody
Resp $reg
$TOKEN = $reg.accessToken
$AUTH  = @{ Authorization = "Bearer $TOKEN" }

# ── 4. Login ──────────────────────────────────────────────────────────────────
Step 4 "Login"
Info "Authenticates with email + password, returns a fresh JWT."
Info "The token from register is still valid -- login is shown here for completeness."
$loginBody = @{ email = $DEMO_EMAIL; password = "password123" } | ConvertTo-Json
Req "POST" "$BASE_URL/api/v1/auth/login"
ReqBody $loginBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/auth/login" -ContentType "application/json" -Body $loginBody)

# ── 5. List plans ─────────────────────────────────────────────────────────────
Step 5 "List plans"
Info "Returns the billing-cadence catalogue: Monthly (30 days / 199), Quarterly (90 days / 499),"
Info "and Yearly (365 days / 1,499). When subscribing you pick one plan + one tier."
Info "The plan controls how long the subscription runs; the tier controls which features you get."
Req "GET" "$BASE_URL/api/v1/plans"
$plans  = Invoke-RestMethod "$BASE_URL/api/v1/plans"
Resp $plans
$planId = ($plans | Where-Object { $_.code -eq "MONTHLY" }).id

# ── 6. List tiers ─────────────────────────────────────────────────────────────
Step 6 "List tiers"
Info "Returns the feature-level catalogue: Silver (free), Gold (500), Platinum (1,500)."
Info "Each tier has benefit bindings with per-tier JSONB config -- e.g. Silver gets 2% discount"
Info "on GROCERY; Platinum gets 10% across GROCERY, ELECTRONICS, and FASHION."
Req "GET" "$BASE_URL/api/v1/tiers"
$tiers    = Invoke-RestMethod "$BASE_URL/api/v1/tiers"
Resp $tiers
$silverId = ($tiers | Where-Object { $_.code -eq "SILVER" }).id
$goldId   = ($tiers | Where-Object { $_.code -eq "GOLD" }).id

# ── 7. Wallet balance ─────────────────────────────────────────────────────────
Step 7 "Wallet balance"
Info "Shows current balance. The signup bonus (50,000 by default) should already be credited."
Info "Balance is protected by an @Version optimistic lock -- concurrent debits produce a 409"
Info "CONCURRENT_MODIFICATION rather than silently letting a double-charge through."
Req "GET" "$BASE_URL/api/v1/wallet"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/wallet" -Headers $AUTH)

# ── 8. Subscribe ──────────────────────────────────────────────────────────────
Step 8 "Subscribe - Monthly plan + Silver tier"
Info "Starts a subscription. The wallet is charged planPrice + tierPrice in a single transaction."
Info "An Idempotency-Key header is sent -- if this request is retried, the server returns the"
Info "original response verbatim without charging again. Safe for unreliable networks."
$subBody    = "{`"planId`":$planId,`"tierId`":$silverId}"
$subHeaders = @{ Authorization = "Bearer $TOKEN"; "Idempotency-Key" = "run-sub-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" }
Req "POST" "$BASE_URL/api/v1/users/me/subscriptions"
ReqBody $subBody
$sub   = Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/subscriptions" `
    -Headers $subHeaders -ContentType "application/json" -Body $subBody
Resp $sub
$subId = $sub.id

# ── 9. Wallet transactions ────────────────────────────────────────────────────
Step 9 "Wallet transactions"
Info "Shows the append-only ledger. Rows are never updated or deleted -- every charge and credit"
Info "is a new row. You should see exactly two entries: CREDIT (signup bonus) and DEBIT"
Info "(subscription charge). The idempotency_key column enforces uniqueness at the DB level."
Req "GET" "$BASE_URL/api/v1/wallet/transactions"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/wallet/transactions" -Headers $AUTH)

# ── 10. Membership snapshot ───────────────────────────────────────────────────
Step 10 "Membership snapshot"
Info "The combined view of your active subscription. Two tier fields matter:"
Info "  purchasedTier -- what you paid for (the source of truth for billing)."
Info "  effectiveTier -- what you actually get (may be higher via auto-promotion)."
Info "Right now both are Silver because no promotion has fired yet."
Req "GET" "$BASE_URL/api/v1/users/me/membership"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/users/me/membership" -Headers $AUTH)

# ── 11. Upgrade Silver → Gold ─────────────────────────────────────────────────
Step 11 "Upgrade Silver -> Gold"
Info "Immediately upgrades to Gold. The wallet is charged the prorated price difference:"
Info "  (goldPrice - silverPrice) x (daysRemaining / planDurationDays)"
Info "The subscription status becomes ACTIVE and effectiveTier is Gold right away."
$upBody = "{`"targetTierId`":$goldId}"
Req "POST" "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier"
ReqBody $upBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier" `
    -Headers $AUTH -ContentType "application/json" -Body $upBody)

# ── 12. Downgrade Gold → Silver ───────────────────────────────────────────────
Step 12 "Downgrade Gold -> Silver"
Info "Schedules a downgrade for the end of the current billing period. No money movement."
Info "The status becomes PENDING_DOWNGRADE. The user keeps Gold benefits until endsAt."
Info "A ScheduledTierChange row is written; the nightly maintenance job applies it."
$downBody = "{`"targetTierId`":$silverId}"
Req "POST" "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier"
ReqBody $downBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier" `
    -Headers $AUTH -ContentType "application/json" -Body $downBody)

# ── 13. Place 5 orders → ORDER_COUNT auto-promotion ──────────────────────────
Step 13 "Place 5 orders of 1,000 each"
Info "Each order fires the eligibility engine after the DB transaction commits."
Info "The ORDER_COUNT rule (5+ orders within 30 days -> Gold) evaluates on every order."
Info "After the 5th order a TierPromotion record is written. purchasedTier stays Gold"
Info "(no billing change) -- the promotion only lifts effectiveTier."
for ($i = 1; $i -le 5; $i++) {
    $orderBody = '{"amount":1000}'
    Req "POST" "$BASE_URL/api/v1/orders  (order $i / 5)"
    ReqBody $orderBody
    Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/orders" `
        -Headers $AUTH -ContentType "application/json" -Body $orderBody)
}
Start-Sleep 1

# ── 14. Reevaluate eligibility ────────────────────────────────────────────────
Step 14 "Re-evaluate eligibility"
Info "Manually triggers the promotion rule engine for the current user."
Info "Normally fires automatically after each order via an event listener."
Info "Useful when you want to force a re-check without placing another order."
Req "POST" "$BASE_URL/api/v1/users/me/eligibility/reevaluate"
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/eligibility/reevaluate" -Headers $AUTH)

# ── 15. Membership — auto-promoted to Gold ────────────────────────────────────
Step 15 "Membership snapshot - after ORDER_COUNT promotion"
Info "effectiveTier should now show Gold from the auto-promotion."
Info "Look for the activePromotion field: it shows the rule that fired and the expiry date."
Info "purchasedTier remains Gold (from the manual upgrade in step 11)."
Req "GET" "$BASE_URL/api/v1/users/me/membership"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/users/me/membership" -Headers $AUTH)

# ── 16. Big order → MONTHLY_ORDER_VALUE auto-promotion ───────────────────────
Step 16 "Place one order of 25,000"
Info "Running monthly total is now 5x1,000 + 25,000 = 30,000, which exceeds the"
Info "MONTHLY_ORDER_VALUE rule threshold of 20,000. The Platinum TierPromotion is created."
Info "Again, this is free -- purchasedTier (Gold) and billing are unaffected."
$bigBody = '{"amount":25000}'
Req "POST" "$BASE_URL/api/v1/orders"
ReqBody $bigBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/orders" `
    -Headers $AUTH -ContentType "application/json" -Body $bigBody)
Start-Sleep 1

# ── 17. Membership — auto-promoted to Platinum ────────────────────────────────
Step 17 "Membership snapshot - after MONTHLY_ORDER_VALUE promotion"
Info "effectiveTier should now be Platinum."
Info "activePromotion.reason shows which rule fired (MONTHLY_ORDER_VALUE:rule_id=3)."
Info "activePromotion.validUntil matches endsAt -- the promotion expires when the period ends."
Req "GET" "$BASE_URL/api/v1/users/me/membership"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/users/me/membership" -Headers $AUTH)

# ── 18. Cancel subscription ───────────────────────────────────────────────────
Step 18 "Cancel subscription"
Info "Cancels the subscription. Status moves to CANCELLED."
Info "Access is retained until endsAt -- the user keeps their current effectiveTier"
Info "until the period ends (standard SaaS grace period, no pro-rata refund)."
Req "DELETE" "$BASE_URL/api/v1/users/me/subscriptions/$subId"
Resp (Invoke-RestMethod -Method DELETE "$BASE_URL/api/v1/users/me/subscriptions/$subId" -Headers $AUTH)

# ── 19. Idempotent cancel replay ──────────────────────────────────────────────
Step 19 "Idempotent cancel replay"
Info "Sends the exact same DELETE again. The server recognises the already-cancelled state"
Info "and returns the same response without any side-effects."
Info "The response header Idempotent-Replay: true confirms this was a replay."
Req "DELETE" "$BASE_URL/api/v1/users/me/subscriptions/$subId"
Resp (Invoke-RestMethod -Method DELETE "$BASE_URL/api/v1/users/me/subscriptions/$subId" -Headers $AUTH)

Write-Host "`n[OK] All API endpoints exercised successfully." -ForegroundColor Green
Write-Host ""

# ── tests ─────────────────────────────────────────────────────────────────────
if (-not $SkipTests) {
    Write-Host "Running unit and integration tests (TestContainers spins up its own containers) ..."
    mvn verify
    if ($LASTEXITCODE -ne 0) { exit 1 }
    Write-Host ""
    Write-Host "All tests passed."
}

Write-Host "Done. Run 'docker compose down' to stop all services."
