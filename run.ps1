# Builds Docker images, starts all services, exercises every API endpoint,
# then runs the full test suite.
#
# Prerequisites: Docker Desktop, Java 17+, Maven 3.9+
#
# Usage:
#   .\run.ps1                    build + start + api demo + all tests
#   .\run.ps1 -SkipTests         build + start + api demo only
#   .\run.ps1 -Port 8090         use a different port

param(
    [int]$Port = 8080,
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
$healthy = $false
for ($i = 1; $i -le 24; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $healthy = $true; break }
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

# ── API walkthrough ────────────────────────────────────────────────────────────
$BASE_URL    = "http://localhost:$Port"
$DEMO_EMAIL  = "run-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"
$SEP         = "=" * 56

function Banner([string]$title) {
    Write-Host "`n$SEP" -ForegroundColor White
    Write-Host "  $title" -ForegroundColor White
    Write-Host "$SEP" -ForegroundColor White
}

function Step([int]$n, [string]$label) {
    Write-Host "`n-- Step $n : $label" -ForegroundColor Cyan
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
}

Banner "API Walkthrough - all 12 endpoints exercised in sequence"

# ── 1. Ping ───────────────────────────────────────────────────────────────────
Step 1 "Ping"
Req "GET" "$BASE_URL/api/v1/ping"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/ping")

# ── 2. Health ─────────────────────────────────────────────────────────────────
Step 2 "Health check"
Req "GET" "$BASE_URL/actuator/health"
Resp (Invoke-RestMethod "$BASE_URL/actuator/health")

# ── 3. Register ───────────────────────────────────────────────────────────────
Step 3 "Register - creates user + wallet (signup bonus credited automatically)"
$regBody = @{ email = $DEMO_EMAIL; password = "password123"; fullName = "Demo User"; cohort = "REGULAR" } | ConvertTo-Json
Req "POST" "$BASE_URL/api/v1/auth/register"
ReqBody $regBody
$reg = Invoke-RestMethod -Method POST "$BASE_URL/api/v1/auth/register" -ContentType "application/json" -Body $regBody
Resp $reg
$TOKEN = $reg.accessToken
$AUTH  = @{ Authorization = "Bearer $TOKEN" }

# ── 4. Login ──────────────────────────────────────────────────────────────────
Step 4 "Login - returns a fresh JWT"
$loginBody = @{ email = $DEMO_EMAIL; password = "password123" } | ConvertTo-Json
Req "POST" "$BASE_URL/api/v1/auth/login"
ReqBody $loginBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/auth/login" -ContentType "application/json" -Body $loginBody)

# ── 5. List plans ─────────────────────────────────────────────────────────────
Step 5 "List plans - Monthly / Quarterly / Yearly"
Req "GET" "$BASE_URL/api/v1/plans"
$plans  = Invoke-RestMethod "$BASE_URL/api/v1/plans"
Resp $plans
$planId = ($plans | Where-Object { $_.code -eq "MONTHLY" }).id

# ── 6. List tiers ─────────────────────────────────────────────────────────────
Step 6 "List tiers - Silver / Gold / Platinum with benefits"
Req "GET" "$BASE_URL/api/v1/tiers"
$tiers    = Invoke-RestMethod "$BASE_URL/api/v1/tiers"
Resp $tiers
$silverId = ($tiers | Where-Object { $_.code -eq "SILVER" }).id
$goldId   = ($tiers | Where-Object { $_.code -eq "GOLD" }).id

# ── 7. Wallet balance ─────────────────────────────────────────────────────────
Step 7 "Wallet balance - signup bonus should be credited"
Req "GET" "$BASE_URL/api/v1/wallet"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/wallet" -Headers $AUTH)

# ── 8. Subscribe ──────────────────────────────────────────────────────────────
Step 8 "Subscribe - Monthly plan + Silver tier (Idempotency-Key header sent)"
$subBody = "{`"planId`":$planId,`"tierId`":$silverId}"
Req "POST" "$BASE_URL/api/v1/users/me/subscriptions"
ReqBody $subBody
$subHeaders = @{ Authorization = "Bearer $TOKEN"; "Idempotency-Key" = "run-sub-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" }
$sub   = Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/subscriptions" `
    -Headers $subHeaders -ContentType "application/json" -Body $subBody
Resp $sub
$subId = $sub.id

# ── 9. Wallet transactions ────────────────────────────────────────────────────
Step 9 "Wallet transactions - signup credit + subscription debit"
Req "GET" "$BASE_URL/api/v1/wallet/transactions"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/wallet/transactions" -Headers $AUTH)

# ── 10. Membership snapshot ───────────────────────────────────────────────────
Step 10 "Membership snapshot - purchasedTier and effectiveTier both Silver"
Req "GET" "$BASE_URL/api/v1/users/me/membership"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/users/me/membership" -Headers $AUTH)

# ── 11. Upgrade Silver → Gold ─────────────────────────────────────────────────
Step 11 "Upgrade Silver -> Gold (prorated charge, takes effect immediately)"
$upBody = "{`"targetTierId`":$goldId}"
Req "POST" "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier"
ReqBody $upBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier" `
    -Headers $AUTH -ContentType "application/json" -Body $upBody)

# ── 12. Downgrade Gold → Silver ───────────────────────────────────────────────
Step 12 "Downgrade Gold -> Silver (scheduled for period end, no charge)"
$downBody = "{`"targetTierId`":$silverId}"
Req "POST" "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier"
ReqBody $downBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/subscriptions/$subId/change-tier" `
    -Headers $AUTH -ContentType "application/json" -Body $downBody)

# ── 13. Place 5 orders → ORDER_COUNT auto-promotion ──────────────────────────
Step 13 "Place 5 orders of 1000 - ORDER_COUNT rule fires, auto-promotes to Gold"
for ($i = 1; $i -le 5; $i++) {
    $orderBody = '{"amount":1000}'
    Req "POST" "$BASE_URL/api/v1/orders  (order $i / 5)"
    ReqBody $orderBody
    Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/orders" `
        -Headers $AUTH -ContentType "application/json" -Body $orderBody)
}
Start-Sleep 1

# ── 14. Reevaluate eligibility ────────────────────────────────────────────────
Step 14 "Re-evaluate eligibility (explicit trigger)"
Req "POST" "$BASE_URL/api/v1/users/me/eligibility/reevaluate"
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/users/me/eligibility/reevaluate" -Headers $AUTH)

# ── 15. Membership — auto-promoted to Gold ────────────────────────────────────
Step 15 "Membership snapshot - effectiveTier should now be Gold (auto-promotion)"
Req "GET" "$BASE_URL/api/v1/users/me/membership"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/users/me/membership" -Headers $AUTH)

# ── 16. Big order → MONTHLY_ORDER_VALUE auto-promotion ───────────────────────
Step 16 "Place order of 25000 - monthly total exceeds 20000, promotes to Platinum"
$bigBody = '{"amount":25000}'
Req "POST" "$BASE_URL/api/v1/orders"
ReqBody $bigBody
Resp (Invoke-RestMethod -Method POST "$BASE_URL/api/v1/orders" `
    -Headers $AUTH -ContentType "application/json" -Body $bigBody)
Start-Sleep 1

# ── 17. Membership — auto-promoted to Platinum ────────────────────────────────
Step 17 "Membership snapshot - effectiveTier should now be Platinum"
Req "GET" "$BASE_URL/api/v1/users/me/membership"
Resp (Invoke-RestMethod "$BASE_URL/api/v1/users/me/membership" -Headers $AUTH)

# ── 18. Cancel subscription ───────────────────────────────────────────────────
Step 18 "Cancel subscription (access retained until period end)"
Req "DELETE" "$BASE_URL/api/v1/users/me/subscriptions/$subId"
Resp (Invoke-RestMethod -Method DELETE "$BASE_URL/api/v1/users/me/subscriptions/$subId" -Headers $AUTH)

# ── 19. Idempotent cancel replay ──────────────────────────────────────────────
Step 19 "Idempotent replay - same DELETE returns the same response verbatim"
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
