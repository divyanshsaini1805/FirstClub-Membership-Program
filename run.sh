#!/usr/bin/env bash
# Builds Docker images, starts all services (Postgres + Redis + app), and runs tests.
#
# Prerequisites: Docker Desktop, Java 17+, Maven 3.9+
#
# Usage:
#   ./run.sh                   build + start + run all tests
#   ./run.sh --skip-tests      build + start only
#   PORT=8090 ./run.sh         use a different port

set -euo pipefail

PORT=${PORT:-8080}
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

# ── tests ─────────────────────────────────────────────────────────────────────
if [[ "$SKIP_TESTS" == "false" ]]; then
  echo "Running unit and integration tests (TestContainers will spin up its own containers) ..."
  mvn verify
  echo ""
  echo "All tests passed."
fi

echo "Done. Run 'docker compose down' to stop all services."
