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

```bash
# One command — Postgres, Redis, and the app
docker compose up --build

# Then
open http://localhost:8080/swagger-ui.html
curl http://localhost:8080/api/v1/ping
```

If port 8080 is taken on your host, set `APP_PORT` to a free one:

```bash
APP_PORT=8090 docker compose up --build
```

For dev loop:

```bash
docker compose up -d postgres redis
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn spring-boot:run
```

### End-to-end demo

```bash
BASE=http://localhost:8080 ./scripts/demo.sh
```

The script walks register → subscribe → upgrade → schedule-downgrade →
orders → auto-promotion → cancel → idempotent replay, printing the response
at each step.

### Tests

```bash
mvn test            # unit tests
mvn verify          # + Testcontainers-backed integration tests (needs Docker)
```

The integration tests start their own Postgres + Redis containers, so
nothing about your environment matters apart from Docker being up.

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

| Method | Path | Auth |
| --- | --- | --- |
| `POST` | `/api/v1/auth/register` | public |
| `POST` | `/api/v1/auth/login` | public |
| `GET`  | `/api/v1/plans` | public |
| `GET`  | `/api/v1/tiers` | public |
| `GET`  | `/api/v1/wallet` | bearer |
| `GET`  | `/api/v1/wallet/transactions` | bearer |
| `GET`  | `/api/v1/users/me/membership` | bearer |
| `POST` | `/api/v1/users/me/subscriptions` | bearer, supports `Idempotency-Key` |
| `POST` | `/api/v1/users/me/subscriptions/{id}/change-tier` | bearer, supports `Idempotency-Key` |
| `DELETE` | `/api/v1/users/me/subscriptions/{id}` | bearer |
| `POST` | `/api/v1/orders` | bearer |
| `POST` | `/api/v1/users/me/eligibility/reevaluate` | bearer |
| `GET`  | `/actuator/health` / `/swagger-ui.html` / `/v3/api-docs` | public |

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
