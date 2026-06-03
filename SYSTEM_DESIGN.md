# System Design & Interview Prep — FirstClub Membership Program

## Project in One Sentence

A backend that lets users buy tiered memberships (Silver / Gold / Platinum), pay via a
virtual wallet, and get automatically promoted to higher tiers when they place enough
orders — all handled safely under concurrent traffic.

---

## Big Picture — What Exists and Why

```
Browser / Postman / run.ps1
        │
        ▼
┌─────────────────────────────────────────────────────┐
│              Spring Boot App (port 8080)            │
│                                                     │
│  Auth ──► Wallet ──► Subscription ──► Eligibility  │
│    │          │            │               │        │
│    └── JWT    └── Ledger   └── Events      └── Rules│
└─────────────────────────────────────────────────────┘
        │                        │
        ▼                        ▼
  PostgreSQL               Redis
  (source of truth)        (cache + distributed locks)
```

There is **no frontend** — just REST APIs. The whole point is to show that the backend
handles money, concurrency, and business rules correctly.

---

## Component-by-Component Walkthrough

### 1. `auth/` — Who are you?

**What it does:** Register, login, issue JWT tokens.

**Flow:**
```
POST /register
  → create User row
  → create Wallet row (same DB transaction)
  → credit wallet with signup bonus (WalletTransaction row)
  → return JWT

POST /login
  → verify password hash (BCrypt)
  → return JWT
```

**Key files:**
- `JwtService` — signs/verifies HS256 tokens using the `JWT_SECRET` env var
- `JwtAuthenticationFilter` — Spring Security filter that runs on every request,
  extracts the Bearer token, validates it, puts the user into `SecurityContextHolder`
- `CurrentUser` / `CurrentUserArgumentResolver` — a custom annotation so controllers
  can write `(@CurrentUser User user)` instead of extracting the principal manually

**Why JWT?** Stateless — the server does not need to store sessions. Any instance can
validate a token without talking to a database.

---

### 2. `wallet/` — The payment system

**What it does:** Each user has one wallet with a balance. All charges go through here.

**Key design — append-only ledger:**
```
wallets table:        id | user_id | balance | version
wallet_transactions:  id | wallet_id | type | amount | idempotency_key
```

`balance` is the running total. `wallet_transactions` is the immutable history — rows
are never updated or deleted. Every charge = new DEBIT row. Every credit = new CREDIT row.

**Why?** Auditing. If a user says "I was charged twice," you can show them the exact
ledger rows. You can also reconstruct the balance from scratch by summing all transactions.

**Idempotency at the ledger level:**
`idempotency_key` has a UNIQUE constraint. If the same charge is attempted twice with
the same key, the second attempt hits a unique constraint violation, which the code
catches and treats as "already done — return the original result."

**Concurrency protection:**
`wallets.version` is an `@Version` field (JPA optimistic locking). If two requests try
to debit the wallet simultaneously:
- Both read version=5
- First commits — version becomes 6
- Second tries to commit with version=5 → DB rejects it → 409 CONCURRENT_MODIFICATION

---

### 3. `plan/` and `tier/` — The catalogue

**Plans** = billing cadence (how long / how much for the subscription itself)
**Tiers** = feature level (what you actually get)

When subscribing you pick **one plan + one tier**. They are independent:
- Monthly plan + Gold tier = 199 (plan) + 500 (tier) = 699 total, renewed every 30 days
- Yearly plan + Silver tier = 1,499 (plan) + 0 (tier) = 1,499, renewed every 365 days

**Benefits** are attached to tiers via `tier_benefits` with a JSONB `config` column:
```json
Silver   → FREE_DELIVERY → {"minOrderValue": 499}
Gold     → FREE_DELIVERY → {"minOrderValue": 199}
Platinum → FREE_DELIVERY → {"minOrderValue": 0}
```

Adding a new benefit type = one new strategy class + one new row in `tier_benefits`.
No schema change needed.

Both services cache their results in Redis with a 10-minute TTL — plans and tiers
almost never change so there is no need to hit Postgres on every request.

---

### 4. `subscription/` — The core of the project

#### Subscribe
```
POST /api/v1/users/me/subscriptions
Body: { planId: 1, tierId: 1 }

1. Check no active subscription exists
2. Check idempotency key (has this exact request been done before?)
3. Acquire pessimistic row lock on subscription (no concurrent subscribes)
4. Charge wallet via BillingService
5. Create Subscription row (status=ACTIVE)
6. Write SubscriptionEvent row (type=SUBSCRIBED)
7. Invalidate membership cache
8. Save idempotency result
9. Return subscription
```

#### Upgrade (Silver → Gold)
```
POST /api/v1/users/me/subscriptions/1/change-tier
Body: { targetTierId: 2 }   ← Gold has higher rank than Silver

1. Lock subscription row (pessimistic)
2. Calculate prorated charge:
   charge = (goldPrice - silverPrice) × (daysRemaining / planDurationDays)
   e.g. (500 - 0) × (28/30) = 466.67
3. Charge wallet
4. Update Subscription.purchasedTier = Gold
5. Write SubscriptionEvent (type=TIER_UPGRADED)
6. Invalidate cache
```

#### Downgrade (Gold → Silver)
```
POST /api/v1/users/me/subscriptions/1/change-tier
Body: { targetTierId: 1 }   ← Silver has lower rank

1. Lock subscription row
2. No charge (downgrade = no money movement)
3. Write ScheduledTierChange row (target=Silver, effectiveAt=subscription.endsAt)
4. Set Subscription.status = PENDING_DOWNGRADE
5. Write SubscriptionEvent (type=TIER_DOWNGRADE_SCHEDULED)
6. Invalidate cache
   ← user keeps current tier until period ends
   ← nightly job applies the ScheduledTierChange when endsAt arrives
```

#### Cancel
```
DELETE /api/v1/users/me/subscriptions/1

1. Lock subscription row
2. Set status = CANCELLED
3. Write SubscriptionEvent (type=CANCELLED)
4. Invalidate cache
   ← no refund, access retained until endsAt
```

#### purchasedTier vs effectiveTier — the most important concept

```
purchasedTier = what you paid for (stored in DB, used for billing)
effectiveTier = what you actually get right now

effectiveTier = max(purchasedTier, any active auto-promotion)
```

If you bought Silver but ORDER_COUNT promoted you to Gold, you still only *pay* Silver
prices on renewal. Gold features are a bonus that expires at period end.

#### MembershipSnapshotCache

`GET /users/me/membership` is the most-read endpoint. Instead of joining 6 tables on
every call, the result is serialised into Redis on first read. Every write publishes a
`SnapshotInvalidatedEvent` that the `SnapshotInvalidationListener` catches and wipes
the cache key. The next read recomputes from DB and re-caches.

---

### 5. `eligibility/` — Auto-promotion engine

**What it does:** Watches for orders. When a user qualifies for a higher tier, creates
a temporary `TierPromotion` that lifts `effectiveTier` without touching billing.

**Flow:**
```
User places order
  → OrderService saves Order row
  → Publishes OrderPlacedEvent (AFTER_COMMIT — only if transaction succeeded)
  → OrderPlacedEligibilityListener receives it
  → EligibilityCoordinator acquires per-user Redis lock
      (prevents concurrent orders from racing on tier writes)
  → TierEligibilityService evaluates all active rules
  → If any rule passes → write TierPromotion row
  → Invalidate membership cache
```

**Three rules (Strategy pattern):**

| Rule | Condition | Promotes to |
| --- | --- | --- |
| `OrderCountRule` | 5+ orders in last 30 days | Gold |
| `MonthlyOrderValueRule` | Monthly total ≥ 20,000 | Platinum |
| `CohortRule` | User cohort = VIP_BETA | Platinum |

Adding a new rule = one new class implementing `PromotionRuleEvaluator` + one new row
in `tier_promotion_rules`. The dispatcher picks it up automatically.

**Redis lock in `EligibilityCoordinator`:**
Lock key: `lock:tier-eval:user:{userId}`, TTL: 15 seconds.
Ensures concurrent orders for the same user serialise on tier writes. If the JVM
crashes while holding the lock, the TTL auto-expires — no permanent deadlock.

---

### 6. `billing/` — Payment abstraction

```java
interface PaymentMethod {
    void charge(Wallet wallet, BigDecimal amount, String idempotencyKey);
}

class WalletPaymentMethod implements PaymentMethod { ... }
// Future: class StripePaymentMethod implements PaymentMethod { ... }
```

`BillingService` calls `charge()` through the interface. The subscription code never
knows whether money came from a wallet or Stripe. Swapping PSPs = add one class.

---

### 7. `common/` — Cross-cutting concerns

#### Idempotency
```
HTTP request arrives with header: Idempotency-Key: abc-123

1. Hash (userId + key + endpoint)
2. Check idempotency_keys table
3a. Found → return stored response, set Idempotent-Replay: true header
3b. Not found → process request → store response → return response
```

In-flight duplicate (two simultaneous requests with the same key): the first writes a
"pending" marker; the second sees it and gets a 409 — retry after the first finishes.

#### Distributed lock
Thin wrapper around Redisson's `RLock`. Used by `EligibilityCoordinator`. 15-second
lease auto-expires on JVM crash.

#### Error handling
All errors return RFC 7807 `application/problem+json`:
```json
{
  "type": "about:blank",
  "title": "Insufficient Funds",
  "status": 402,
  "code": "INSUFFICIENT_FUNDS",
  "detail": "Wallet balance 100.00 is below required 500.00"
}
```
The `code` field is stable across API versions — clients switch on `code`, not prose.

---

## HLD — High Level Design

```
                    ┌──────────────┐
                    │   Client     │
                    │ (REST calls) │
                    └──────┬───────┘
                           │ HTTPS
                    ┌──────▼───────┐
                    │  Spring Boot │
                    │     App      │
                    │  ┌─────────┐ │
                    │  │Security │ │  ← JWT validation on every request
                    │  │ Filter  │ │
                    │  └────┬────┘ │
                    │  ┌────▼────┐ │
                    │  │Controllers│ ← Route to services
                    │  └────┬────┘ │
                    │  ┌────▼────┐ │
                    │  │Services │ │  ← Business logic
                    │  └────┬────┘ │
                    └───────┼──────┘
               ┌────────────┼────────────┐
               ▼            ▼            ▼
        ┌────────────┐  ┌────────┐  ┌──────────┐
        │ PostgreSQL │  │ Redis  │  │  Redis   │
        │ (JPA /     │  │ Cache  │  │  Locks   │
        │  Flyway)   │  │ (TTL)  │  │(Redisson)│
        └────────────┘  └────────┘  └──────────┘
```

**What goes where:**
- **PostgreSQL** — all source-of-truth data (users, wallets, subscriptions, events, ledger)
- **Redis cache** — membership snapshots (1 h TTL), plans/tiers (10 min TTL)
- **Redis locks** — per-user tier evaluation lock, in-flight idempotency markers

**Scalability notes:**
- App is stateless (JWT, no sessions) → multiple instances behind a load balancer
- Redis is shared across instances → locks and cache work correctly with N pods
- Postgres is the scale bottleneck → sharding by `user_id` or a distributed DB at scale
- Eligibility engine uses in-process events today → replace with Kafka for independent scaling

---

## LLD — Low Level Design

### Database Schema (key relationships)

```
users (1) ──── (1) wallets
  │                   │
  │             wallet_transactions (many, append-only)
  │
  └──── (1) subscriptions (1 active at a time per user)
                │
                ├── plan_id ──────────────────► plans
                ├── purchased_tier_id ─────────► tiers
                │
                ├── subscription_events (many, append-only audit)
                └── scheduled_tier_changes (0 or 1 pending downgrade)

tiers (1) ──── (many) tier_benefits ──── (1) benefits

tier_promotion_rules  ← evaluated by eligibility engine
tier_promotions       ← written when a rule passes (temporary, expires at period end)

orders                ← triggers eligibility evaluation on each insert
idempotency_keys      ← stores request hash → serialised response
```

### Class Interaction — Subscribe

```
SubscriptionController.subscribe()
  └── SubscriptionLifecycleService.subscribe()
        ├── IdempotencyService.check()                  ← return early if replay
        ├── SubscriptionRepository.lockCurrentByUserId() ← pessimistic row lock
        ├── BillingService.charge()
        │     └── WalletPaymentMethod.charge()
        │           └── WalletService.debit()
        │                 └── WalletTransactionRepository.save()  ← ledger row
        ├── SubscriptionRepository.save()               ← subscription row
        ├── SubscriptionEventRepository.save()          ← audit row
        ├── applicationEventPublisher.publish(SnapshotInvalidatedEvent)
        │     └── SnapshotInvalidationListener → Redis.delete(cacheKey)
        └── IdempotencyService.store(response)
```

---

## Design Patterns Used

| Pattern | Where in code | Why |
| --- | --- | --- |
| **Strategy** | `PaymentMethod`, `PromotionRuleEvaluator` | Swap PSP or add promotion rules without touching existing code |
| **Observer / Event** | `OrderPlacedEvent`, `SnapshotInvalidatedEvent` | Decouple order placement from eligibility evaluation and cache invalidation |
| **Repository** | All `*Repository` interfaces | Abstract DB access; Spring Data generates queries from method names |
| **Optimistic Locking** | `Wallet.version`, `Subscription.version` | Detect concurrent writes without holding a DB lock for the full operation |
| **Pessimistic Locking** | `lockCurrentByUserId()` | Guarantee only one subscription change happens at a time per user |
| **Cache-Aside** | `MembershipSnapshotCache` | Read from cache; on miss load from DB and populate; invalidate on write |
| **Idempotency** | `IdempotencyService`, `WalletTransaction.idempotency_key` | Safe retries at both HTTP layer and DB layer |
| **Append-Only / Event Sourcing (lite)** | `WalletTransaction`, `SubscriptionEvent` | Immutable audit trail; never lose history |
| **Distributed Lock** | `EligibilityCoordinator` via Redisson | Serialise concurrent eligibility evaluations for the same user |
| **Factory** | `Errors` class | Centralise error code creation so they stay consistent |
| **Facade** | `BillingService` | Single entry point for charging regardless of payment method |
| **DTO / Mapper** | `PlanDto`, `TierDto`, `SubscriptionDto` | Never expose JPA entities directly; control what the API leaks |

---

## Interview Questions & Detailed Answers

### Architecture

---

**Q: Why package-by-feature instead of package-by-layer (controllers/, services/, repositories/)?**

Package-by-feature puts everything related to one domain concept (`subscription/`,
`wallet/`) together. When changing subscription logic, every file is in one folder.
Package-by-layer scatters a feature across 4 folders — you jump constantly. It also
makes cross-module dependencies visible: if `subscription/` calls `wallet/`, that is
an intentional dependency I can reason about, not an implicit coupling hidden by
layering.

---

**Q: Why did you choose Redis for both caching and distributed locks?**

They are different concerns but Redis fits both well and it is one less infrastructure
component to operate. For caching, Spring's cache abstraction with TTL is used — if
Redis goes down the app falls back to Postgres (slower but correct). For locks,
Redisson implements Redlock on top of Redis. The key property needed from a lock is
auto-expiry if the holder crashes — Redis TTL gives that for free.

---

### Concurrency

---

**Q: You use both optimistic locks (`@Version`) and pessimistic locks. When do you use which?**

- **Optimistic lock** (`@Version` on `Wallet` and `Subscription`): used when conflicts
  are *rare*. Both threads read, do work, then try to commit. If someone else committed
  in between, the version mismatch causes a 409. Zero cost when there is no conflict,
  retry cost when there is.

- **Pessimistic lock** (`SELECT FOR UPDATE` via `lockCurrentByUserId`): used for
  subscription mutations where two threads must not even *read* the same state and
  proceed in parallel. Tier change logic reads current tier and makes decisions on it —
  no one else can read+act on that row until the current transaction commits.

Rule of thumb: wallet debits use optimistic (concurrent debits for different payments
are fine to retry); subscription mutations use pessimistic (state-dependent decisions
must be serialised).

---

**Q: What happens if two concurrent requests try to subscribe for the same user?**

The pessimistic row lock means one gets the lock first, sees "no active subscription,"
creates one, commits. The second gets the lock after, re-checks, finds an active
subscription, returns 409 `SUBSCRIPTION_ALREADY_ACTIVE`. The check-after-lock pattern
(re-validating state after acquiring the lock) is essential — without it both could
read "no subscription" before either commits and you'd create two.

---

**Q: Why is the eligibility event listener `AFTER_COMMIT` instead of calling the service directly?**

If called *inside* the same transaction as the order:
1. The order is not visible to other DB connections yet (uncommitted data)
2. If eligibility evaluation fails, it rolls back the order too — wrong
3. The two operations are logically separate: placing an order should always succeed;
   promotion is a bonus side-effect

`AFTER_COMMIT` fires only if the order transaction committed successfully. If the order
DB write fails, no eligibility evaluation happens. Causally linked but decoupled.

---

### Idempotency

---

**Q: Explain your two-layer idempotency approach.**

**Layer 1 — HTTP level (`IdempotencyService`):**
Clients send an `Idempotency-Key` header. The server stores a hash of
`(userId + key + endpoint) → serialised response`. On retry, returns the stored
response verbatim without re-executing business logic. Handles network retries —
the client gets a timeout, retries subscribe, and is not charged twice.

**Layer 2 — Ledger level (`wallet_transactions.idempotency_key` UNIQUE):**
Even if Layer 1 is bypassed (bug, different code path), the charge itself has a unique
key. Attempting to write the same charge twice hits a unique constraint violation,
caught and treated as "already done."

Why both? Defense in depth. The HTTP layer handles happy-path retries. The DB layer is
the safety net that prevents data corruption even if the application layer has a bug.

---

**Q: What is an in-flight duplicate and how do you handle it?**

An in-flight duplicate is two *simultaneous* requests with the same idempotency key
before either has finished — different from a retry (where the first is already done).

Handling: the first request writes a "pending" marker to `idempotency_keys`. If a
second request arrives and sees a pending marker, it throws `InFlightDuplicateException`
→ 409. The client retries after a short wait; by then the first has finished and the
retry gets the cached response from Layer 1.

---

### Business Logic

---

**Q: How is `effectiveTier` computed?**

```
effectiveTier = the highest of:
  1. purchasedTier (what the user explicitly paid for)
  2. any active TierPromotion (where expiresAt > now)
```

`TierPromotion` rows are written by the eligibility engine, each with a `targetTierRank`.
The system picks the TierPromotion with the highest rank that has not expired. If that
rank is higher than `purchasedTier.rank`, the effective tier is the promotion. Otherwise
it is `purchasedTier`.

This separation is critical for billing: `purchasedTier` is what you charge on renewal.
Auto-promotions are free and temporary. If they were merged into one field, there would
be no way to know what to charge on renewal.

---

**Q: How does proration work for an upgrade?**

```
proratedCharge = (newTierPrice − currentTierPrice) × (daysRemaining / totalPlanDays)
```

Example — upgrading Silver (0) → Gold (500) with 28 of 30 days remaining:
```
(500 − 0) × (28 / 30) = 466.67
```

The user pays for the *remaining* time at the higher tier. They have already paid for
the elapsed time at Silver. This is the standard SaaS proration model (same as Stripe).

---

**Q: Why is a downgrade scheduled instead of immediate?**

Two business reasons:
1. **Revenue**: the user already paid for Gold for the month; giving them Silver
   immediately is unfair.
2. **User trust**: standard SaaS behaviour — users expect "your plan changes on the
   next billing date."

Technically: a `ScheduledTierChange` row is written with `effectiveAt = subscription.endsAt`.
The nightly `SubscriptionMaintenanceJob` picks up all rows where `effectiveAt <= now`
and applies them.

---

### Scaling / Production Readiness

---

**Q: The membership snapshot is cached. What happens if the cache goes stale?**

Every mutation path (subscribe, change-tier, cancel, auto-promotion) publishes a
`SnapshotInvalidatedEvent` synchronously before returning. The listener deletes the
Redis key. The next read misses the cache, queries Postgres, and repopulates.

The only stale-read scenario: the listener fails after the DB write but before the
cache delete. In that case, the cache holds a stale snapshot until TTL (1 hour)
expires. For a membership snapshot that does not drive billing decisions, eventual
consistency within 1 hour is acceptable. For stronger guarantees, write through the
cache inside the DB transaction using a transactional outbox pattern.

---

**Q: How would you add Stripe as a payment method?**

```java
class StripePaymentMethod implements PaymentMethod {
    @Override
    public void charge(Wallet wallet, BigDecimal amount, String idempotencyKey) {
        stripeClient.charges().create(
            amount, "inr", wallet.getStripeCustomerId(), idempotencyKey
        );
    }
}
```

Inject based on user preference or a feature flag in `BillingService`.
`SubscriptionLifecycleService` never changes — it only calls `billingService.charge()`
and is agnostic to what PaymentMethod is underneath.

---

**Q: How would you scale this to 10 million users?**

1. **App layer**: already stateless (JWT). Add instances behind a load balancer.
   Kubernetes HPA on CPU.
2. **Postgres**: read replicas for read-heavy endpoints (plans, tiers, membership).
   Subscription writes stay on primary. Partition `wallet_transactions` and
   `subscription_events` by `user_id` range — these tables grow unboundedly.
3. **Redis**: move to Redis Cluster for horizontal scaling of cache and locks.
4. **Eligibility engine**: put `OrderPlacedEvent` on Kafka instead of in-process.
   Eligibility workers consume from the topic independently, scale separately.
5. **Maintenance job**: at scale, the nightly job scanning all subscriptions becomes
   slow. Replace with per-shard jobs or CDC (Change Data Capture) triggers.

---

**Q: What would you do differently for a production system?**

1. **Outbox pattern** instead of in-process events for cache invalidation. If the app
   crashes after the DB write but before the event fires, the cache is never
   invalidated. An outbox table (events written in the same transaction, published by
   a separate poller) gives exactly-once delivery.
2. **Refresh tokens** — the current JWT has a 24-hour TTL with no revocation. In
   production: short-lived access tokens (15 min) + long-lived refresh tokens stored
   server-side so they can be revoked.
3. **Dead-letter handling** for failed eligibility evaluations — if the Redis lock
   cannot be acquired after N retries, write to a retry queue.
4. **Observability** — Micrometer metrics for wallet charges, subscription creates,
   cache hit/miss ratios, lock contention. Currently only logs exist.
5. **Auto-renewal** — subscriptions expire and rely on the user to re-subscribe. Real
   systems use the outbox pattern to trigger a renewal job at `endsAt − buffer`.

---

**Q: Why Flyway for DB migrations?**

Flyway tracks applied SQL scripts in `flyway_schema_history`. On startup it runs
anything new. This means:
- The schema is version-controlled alongside the code
- Multiple developers can work on schema changes without conflicts
- Rollbacks are explicit (a new migration, not undoing an old one)
- `spring.jpa.hibernate.ddl-auto=validate` means Hibernate only checks the schema
  matches the entities — it never auto-creates or auto-drops tables in production.

---

## Things to Say Proactively in the Interview

1. **"purchasedTier vs effectiveTier"** — state this distinction once and explain why
   it matters for billing. Shows you thought about the hard edge case.

2. **"Two layers of idempotency"** — HTTP-level and DB-level. Most candidates think of
   only one.

3. **"AFTER_COMMIT event listener"** — shows you know why eligibility evaluation cannot
   run inside the order transaction.

4. **"Append-only ledger"** — explain that `wallet_transactions` rows are never updated
   and why that matters for auditing and correctness.

5. **"Nightly job applies ScheduledTierChanges"** — do not just say "downgrade is
   scheduled," explain what actually applies it and when.

6. **"Redisson lock TTL auto-expires if the JVM crashes"** — shows you thought about
   failure scenarios, not only the happy path.
