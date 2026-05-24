# Builds Docker images, starts all services (Postgres + Redis + app), and runs tests.
#
# Prerequisites: Docker Desktop, Java 17+, Maven 3.9+
#
# Usage:
#   .\run.ps1                    build + start + run all tests
#   .\run.ps1 -SkipTests         build + start only
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

# ── tests ─────────────────────────────────────────────────────────────────────
if (-not $SkipTests) {
    Write-Host "Running unit and integration tests (TestContainers will spin up its own containers) ..."
    mvn verify
    if ($LASTEXITCODE -ne 0) { exit 1 }
    Write-Host ""
    Write-Host "All tests passed."
}

Write-Host "Done. Run 'docker compose down' to stop all services."
