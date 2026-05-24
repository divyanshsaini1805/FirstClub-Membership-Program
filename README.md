# FirstClub Membership Program

Backend for a tiered subscription/membership program. Built with Java 17,
Spring Boot 3.2, PostgreSQL, and Redis.

## What it does

- **Plans** (Monthly / Quarterly / Yearly) — purchased.
- **Tiers** (Silver / Gold / Platinum) — purchased OR earned via auto-promotion.
- **Benefits** (free delivery, % discount, early access, priority support) —
  config-driven per tier, expressed as JSONB so new benefit types ship as a
  single new strategy class.
- **Virtual wallet** per user, seeded on registration, behind a generic
  `PaymentMethod` abstraction so a real PSP (Stripe/Razorpay) can drop in
  without touching the subscription code.
- **Tier change**:
  - **Upgrade** → prorated charge from wallet, takes effect immediately.
  - **Downgrade** → scheduled for current period end (no money movement;
    standard SaaS pattern).
  - **Auto-promotion** (criteria-based) → free, temporary, expires at
    period end; user keeps purchased tier as the source of truth for billing.
- **Concurrency**: optimistic locks (`@Version`) on wallet and subscription,
  pessimistic row lock on tier mutations, Redis distributed lock per user
  around tier evaluation, idempotency keys on subscribe / change-tier (HTTP
  replay) and on every wallet charge (ledger-level).

## Quick start

Two scripts cover everything — pick the one for your OS.

**Prerequisites:** [Docker Desktop](https://www.docker.com/products/docker-desktop/),
Java 17+, Maven 3.9+

### Mac / Linux

```bash
chmod +x run.sh
./run.sh
```

### Windows (PowerShell)

```powershell
.\run.ps1
```

Both scripts:
1. Build the Docker image for the app
2. Start Postgres, Redis, and the app via `docker compose`
3. Wait until the app is healthy
4. Run unit tests and Testcontainers-backed integration tests (`mvn verify`)

After they finish:

| URL | What |
| --- | --- |
| `http://localhost:8080/swagger-ui.html` | Interactive API docs |
| `http://localhost:8080/api/v1/ping` | Smoke-test endpoint |
| `http://localhost:8080/actuator/health` | Health check |

**Options:**

```bash
# Skip tests (faster — just build + start)
./run.sh --skip-tests
.\run.ps1 -SkipTests

# Use a different port (e.g. if 8080 is taken)
PORT=8090 ./run.sh
.\run.ps1 -Port 8090
```

**Stop everything:**

```bash
docker compose down        # stop containers, keep Postgres data
docker compose down -v     # stop containers and wipe Postgres data
```

### Dev loop (app on the host, infra in Docker)

```bash
docker compose up -d postgres redis
mvn spring-boot:run        # uses application-local.yml; hits localhost:5432 and localhost:6379
```

### End-to-end demo

```bash
BASE=http://localhost:8080 ./scripts/demo.sh    # Mac/Linux
$env:BASE = 'http://localhost:8080'; .\scripts\demo.ps1   # Windows
```

The script walks register → subscribe → upgrade → schedule-downgrade →
orders → auto-promotion → cancel → idempotent replay, printing the response
at each step.

## Architecture at a glance

Source is organised **package-by-feature** so module boundaries map onto
domain concepts.

```
com.firstclub.membership
├── auth          register/login, JWT issuer + filter, security config
├── user          User entity, cohort tag
├── wallet        Wallet + immutable ledger, WalletService
├── billing       PaymentMethod (interface), WalletPaymentMethod, BillingService
├── plan          Plan catalogue + read API
├── tier          Tier + Benefit + TierBenefit, read API
├── subscription  Lifecycle service (subscribe/cancel/change-tier),
│                 SubscriptionEvent (append-only audit), proration,
│                 ScheduledTierChange, MaintenanceJob, snapshot view/cache
├── eligibility   TierPromotionRule (strategy interface),
│                 OrderCountRule + MonthlyOrderValueRule + CohortRule,
│                 TierEligibilityService, EligibilityCoordinator (locks)
├── order         Demo-only Order stub + AFTER_COMMIT eligibility trigger
├── common
│   ├── persistence  BaseEntity, AppendOnlyEntity
│   ├── error        ApiException, Errors factory, RFC 7807 handler
│   ├── idempotency  IdempotencyKey + IdempotencyService
│   └── lock         DistributedLockService (Redisson)
└── config        Properties, OpenAPI, Security, WebMvc, Clock bean
```

### Key abstractions worth a look

| Abstraction | Where | What it enables |
| --- | --- | --- |
| `PaymentMethod` (interface) | `billing/` | Wallet today, a PSP tomorrow — subscription code never knows the difference |
| `PromotionRuleEvaluator` (strategy) | `eligibility/` + `eligibility/rules/` | Adding `RegionRule` etc. is one class + one row, no schema change |
| `Benefit` + JSONB `TierBenefit.config` | `tier/` | New benefit types ship as one strategy + one row |
| `SubscriptionEvent` (append-only) | `subscription/` | Full audit history; never delete, never mutate |
| `Subscription.purchasedTier` vs `effectiveTier()` (computed) | `subscription/` | Auto-promotion never silently changes what the user is billed for |
| `IdempotencyService` | `common/idempotency/` | Postgres-durable replay for retried subscribe + change-tier |
| `EligibilityCoordinator` (Redisson lock) | `eligibility/` | Concurrent orders for the same user serialize on tier writes |
| `MembershipSnapshotCache` (Redis) | `subscription/` | `/membership` reads served from cache; invalidated on every mutation |

### Concurrency story

| Concern | Mechanism |
| --- | --- |
| Concurrent change-tier on the same subscription | Pessimistic row lock via `SubscriptionRepository#lockCurrentByUserId` |
| Wallet double-charge on retry | `idempotency_key` UNIQUE per wallet; charge replays the original ledger row |
| Subscribe / change-tier retry replay | HTTP `Idempotency-Key` → `IdempotencyService` returns the original response verbatim with header `Idempotent-Replay: true` |
| Concurrent orders racing on tier eval | Per-user Redis lock (`lock:tier-eval:user:{id}`) acquired by `EligibilityCoordinator` |
| Stale optimistic state | `@Version` on `Wallet` and `Subscription` → 409 `CONCURRENT_MODIFICATION` |
| Cache invalidation | `SnapshotInvalidatedEvent` published from every lifecycle path; listener wipes the cache key |
| Crashed lock holder | Redisson lock lease (15s) auto-expires; no permanent blocking |

## API surface (v1)

| Method | Path | Auth | What it does |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | public | Creates a user + wallet. Wallet is credited with the signup bonus on creation. Returns a JWT. |
| `POST` | `/api/v1/auth/login` | public | Authenticates with email + password. Returns a fresh JWT. |
| `GET`  | `/api/v1/plans` | public | Lists the billing-cadence catalogue: Monthly (30 d / 199), Quarterly (90 d / 499), Yearly (365 d / 1,499). |
| `GET`  | `/api/v1/tiers` | public | Lists the feature-level catalogue: Silver (free), Gold (500), Platinum (1,500), each with their benefit bindings and per-tier JSONB config. |
| `GET`  | `/api/v1/wallet` | bearer | Returns current wallet balance. Protected by an `@Version` optimistic lock — concurrent debits produce a `409 CONCURRENT_MODIFICATION` instead of a silent double-charge. |
| `GET`  | `/api/v1/wallet/transactions` | bearer | Returns the append-only ledger. Rows are never mutated — every credit and debit is a separate row with a unique `idempotency_key`. |
| `GET`  | `/api/v1/users/me/membership` | bearer | Combined subscription view. `purchasedTier` is what the user paid for (billing source of truth). `effectiveTier` may be higher if an auto-promotion is active. Served from Redis cache; invalidated on every mutation. |
| `POST` | `/api/v1/users/me/subscriptions` | bearer | Starts a subscription. Charges `planPrice + tierPrice` from the wallet in a single transaction. Supports `Idempotency-Key` — retrying the same request returns the original response without a double-charge. |
| `POST` | `/api/v1/users/me/subscriptions/{id}/change-tier` | bearer | **Upgrade** (higher tier): prorated charge applied immediately. **Downgrade** (lower tier): scheduled for period end, no charge. Supports `Idempotency-Key`. |
| `DELETE` | `/api/v1/users/me/subscriptions/{id}` | bearer | Cancels the subscription. Status moves to `CANCELLED`; access is retained until `endsAt`. |
| `POST` | `/api/v1/orders` | bearer | Records an order and fires the eligibility engine after commit. Each order may trigger an auto-promotion rule (ORDER_COUNT or MONTHLY_ORDER_VALUE). |
| `POST` | `/api/v1/users/me/eligibility/reevaluate` | bearer | Manually re-runs all promotion rules for the current user. Normally fires automatically on each order via an event listener; useful for testing. |
| `GET`  | `/actuator/health` | public | Spring Actuator health — reports status of app, Postgres, and Redis. |
| `GET`  | `/swagger-ui.html` | public | Interactive API docs (Springdoc OpenAPI). |
| `GET`  | `/v3/api-docs` | public | Raw OpenAPI spec in JSON. |

Errors follow RFC 7807 (`application/problem+json`) with a stable `code`
field so clients can switch without parsing prose.

## Config knobs

All optional; sensible defaults live in `application.yml`.

| Env var | Default | Purpose |
| --- | --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost / membership / membership | Postgres |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 6379 | Redis |
| `JWT_SECRET` | demo value | HS256 signing key — **change in prod** |
| `WALLET_SIGNUP_BONUS` | `50000.00` | Credited on user registration |
| `SERVER_PORT` | `8080` | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `local` | `local`, `docker`, or `test` |

## Tradeoffs and intentional non-goals

- **Wallet as the only payment method** — exercised end-to-end with proper
  ledgering and idempotency, but the `PaymentMethod` abstraction lets a real
  PSP slot in cleanly.
- **No password reset / email verification / refresh tokens** — auth is
  scoped to "give the reviewer a working bearer token."
- **Single Maven module** — package-by-feature gives the modularity the
  brief asks for without the multi-module overhead.
- **`OpenSession-in-view` is off** — services explicitly fetch joined
  references; we never paper over lazy-init by leaking sessions to the web tier.
- **Auto-renewal** — out of scope, subscriptions expire at `endsAt` and a
  scheduled job flips them to `EXPIRED`.

## Cross-platform notes

- Tested on macOS (Apple Silicon) and Windows 11 + Docker Desktop + WSL2.
- No host paths, no BuildKit-only features. Compose uses named volumes and
  service-name DNS for app↔Postgres↔Redis.
- `docker compose down -v` wipes Postgres data.
- If you already have a Redis on `localhost:6379`, the in-container app uses
  the `redis` service via Docker network, so port collision on the host
  doesn't matter. Local `mvn spring-boot:run` will hit whichever Redis your
  machine has bound to `localhost:6379` — that's fine for the demo.

## License

Internal assessment — not for redistribution.
