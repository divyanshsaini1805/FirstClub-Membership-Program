#!/usr/bin/env pwsh
# End-to-end demo of the FirstClub Membership Program.
#
# Walks through: register → subscribe → upgrade → downgrade → orders →
# auto-promotion. Each step prints a labelled summary so a reviewer can
# follow along.
#
# Usage:
#   .\scripts\demo.ps1                          # default http://localhost:8080
#   $env:BASE = 'http://localhost:8090'; .\scripts\demo.ps1

$ErrorActionPreference = 'Stop'

$BASE     = if ($env:BASE) { $env:BASE } else { 'http://localhost:8080' }
$EMAIL    = "demo-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"
$PASSWORD = 'password123'

function Say([string]$label) {
    Write-Host "`n── $label ─────────────────────────────────────" -ForegroundColor Cyan
}

function Show($obj) {
    ConvertTo-Json $obj -Depth 10
}

# ── Register + wallet ─────────────────────────────────────────────────────────
Say 'Register + auto-create wallet'
$reg = Invoke-RestMethod -Method POST "$BASE/api/v1/auth/register" `
    -ContentType 'application/json' `
    -Body (@{
        email    = $EMAIL
        password = $PASSWORD
        fullName = 'Demo User'
        cohort   = 'REGULAR'
    } | ConvertTo-Json)
Show $reg

$TOKEN = $reg.accessToken
$AUTH  = @{ Authorization = "Bearer $TOKEN" }

# ── Catalogue ─────────────────────────────────────────────────────────────────
Say 'List plans'
Show (Invoke-RestMethod "$BASE/api/v1/plans")

Say 'List tiers'
Show (Invoke-RestMethod "$BASE/api/v1/tiers")

# ── Subscribe ─────────────────────────────────────────────────────────────────
Say 'Subscribe — MONTHLY + SILVER (cheap entry, benefits unlock immediately)'
$sub = Invoke-RestMethod -Method POST "$BASE/api/v1/users/me/subscriptions" `
    -Headers ($AUTH + @{ 'Idempotency-Key' = "demo-sub-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" }) `
    -ContentType 'application/json' `
    -Body '{"planId":1,"tierId":1}'
Show $sub
$SUB_ID = $sub.id

# ── Wallet ────────────────────────────────────────────────────────────────────
Say 'Wallet ledger after subscribe (signup credit + subscription debit)'
Show (Invoke-RestMethod "$BASE/api/v1/wallet/transactions" -Headers $AUTH)

# ── Tier changes ──────────────────────────────────────────────────────────────
Say 'Upgrade SILVER → GOLD — prorated charge'
Show (Invoke-RestMethod -Method POST "$BASE/api/v1/users/me/subscriptions/$SUB_ID/change-tier" `
    -Headers $AUTH -ContentType 'application/json' -Body '{"targetTierId":2}')

Say 'Schedule downgrade GOLD → SILVER (takes effect at period end)'
Show (Invoke-RestMethod -Method POST "$BASE/api/v1/users/me/subscriptions/$SUB_ID/change-tier" `
    -Headers $AUTH -ContentType 'application/json' -Body '{"targetTierId":1}')

# ── Orders / auto-promotion ───────────────────────────────────────────────────
Say 'Place 5 orders of 1000 each — should trigger ORDER_COUNT auto-promotion to GOLD'
1..5 | ForEach-Object {
    Invoke-RestMethod -Method POST "$BASE/api/v1/orders" `
        -Headers $AUTH -ContentType 'application/json' -Body '{"amount":1000}' | Out-Null
}
Start-Sleep -Seconds 1

Say 'Snapshot — effective tier should reflect the auto-promotion'
Show (Invoke-RestMethod "$BASE/api/v1/users/me/membership" -Headers $AUTH)

Say 'Place one big order of 25000 — monthly value > 20k → PLATINUM'
Invoke-RestMethod -Method POST "$BASE/api/v1/orders" `
    -Headers $AUTH -ContentType 'application/json' -Body '{"amount":25000}' | Out-Null
Start-Sleep -Seconds 1

Say 'Snapshot — should be effective=PLATINUM'
Show (Invoke-RestMethod "$BASE/api/v1/users/me/membership" -Headers $AUTH)

# ── Cancel ────────────────────────────────────────────────────────────────────
Say 'Cancel — access retained until period end'
Show (Invoke-RestMethod -Method DELETE "$BASE/api/v1/users/me/subscriptions/$SUB_ID" -Headers $AUTH)

Say 'Idempotent replay: re-running the same DELETE returns the same response'
Show (Invoke-RestMethod -Method DELETE "$BASE/api/v1/users/me/subscriptions/$SUB_ID" -Headers $AUTH)

Write-Host "`n✓ Demo complete" -ForegroundColor Green
