-- Baseline schema for FirstClub Membership Program.
-- Conventions:
--   * snake_case columns, surrogate UUID/BIGSERIAL PKs.
--   * audit columns (created_at, updated_at) on every mutable table.
--   * append-only tables (subscription_event, wallet_transaction, tier_promotion) have no updated_at.
--   * JSONB for config blobs so new benefit/rule types can ship without DDL.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

------------------------------------------------------------
-- Users
------------------------------------------------------------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255),
    cohort          VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_cohort ON users (cohort);

------------------------------------------------------------
-- Wallet + immutable ledger
------------------------------------------------------------
CREATE TABLE wallets (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    balance     NUMERIC(19,2) NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT wallets_balance_nonneg CHECK (balance >= 0)
);

CREATE TABLE wallet_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    wallet_id           BIGINT NOT NULL REFERENCES wallets(id),
    type                VARCHAR(32) NOT NULL,            -- CREDIT, DEBIT
    amount              NUMERIC(19,2) NOT NULL,
    balance_after       NUMERIC(19,2) NOT NULL,
    reference_type      VARCHAR(64),                     -- SUBSCRIPTION, TIER_CHANGE, SIGNUP_BONUS, etc.
    reference_id        VARCHAR(128),
    idempotency_key     VARCHAR(128),
    note                VARCHAR(512),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT wallet_tx_amount_positive CHECK (amount > 0),
    CONSTRAINT wallet_tx_idem_unique UNIQUE (wallet_id, idempotency_key)
);

CREATE INDEX idx_wallet_tx_wallet ON wallet_transactions (wallet_id, created_at DESC);
CREATE INDEX idx_wallet_tx_ref ON wallet_transactions (reference_type, reference_id);

------------------------------------------------------------
-- Plans (billing cadence — Monthly / Quarterly / Yearly)
------------------------------------------------------------
CREATE TABLE plans (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,         -- MONTHLY, QUARTERLY, YEARLY
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    duration_days   INT NOT NULL,                        -- 30 / 90 / 365 for the demo
    base_price      NUMERIC(19,2) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT plans_duration_positive CHECK (duration_days > 0),
    CONSTRAINT plans_price_nonneg CHECK (base_price >= 0)
);

------------------------------------------------------------
-- Tiers (Silver / Gold / Platinum, rank-ordered, data-driven)
------------------------------------------------------------
CREATE TABLE tiers (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    rank            INT NOT NULL UNIQUE,                 -- higher = better
    price           NUMERIC(19,2) NOT NULL,              -- added on top of plan base_price
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT tiers_price_nonneg CHECK (price >= 0)
);

------------------------------------------------------------
-- Benefits (catalogue) + tier_benefits (binding with per-tier config)
------------------------------------------------------------
CREATE TABLE benefits (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64) NOT NULL UNIQUE,         -- FREE_DELIVERY, PERCENT_DISCOUNT, EARLY_ACCESS, PRIORITY_SUPPORT
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tier_benefits (
    id          BIGSERIAL PRIMARY KEY,
    tier_id     BIGINT NOT NULL REFERENCES tiers(id) ON DELETE CASCADE,
    benefit_id  BIGINT NOT NULL REFERENCES benefits(id) ON DELETE RESTRICT,
    config      JSONB NOT NULL DEFAULT '{}'::jsonb,      -- e.g. {"percent": 10, "categories": ["GROCERY"]}
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT tier_benefits_unique UNIQUE (tier_id, benefit_id)
);

CREATE INDEX idx_tier_benefits_tier ON tier_benefits (tier_id) WHERE active = TRUE;

------------------------------------------------------------
-- Subscriptions
------------------------------------------------------------
CREATE TABLE subscriptions (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id                 BIGINT NOT NULL REFERENCES plans(id),
    purchased_tier_id       BIGINT NOT NULL REFERENCES tiers(id),
    status                  VARCHAR(32) NOT NULL,        -- ACTIVE, CANCELLED, EXPIRED, PENDING_DOWNGRADE
    starts_at               TIMESTAMPTZ NOT NULL,
    ends_at                 TIMESTAMPTZ NOT NULL,
    cancelled_at            TIMESTAMPTZ,
    auto_renew              BOOLEAN NOT NULL DEFAULT FALSE,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT subs_period_valid CHECK (ends_at > starts_at)
);

-- A user can have at most one ACTIVE/PENDING_DOWNGRADE subscription at a time.
CREATE UNIQUE INDEX uniq_active_sub_per_user
    ON subscriptions (user_id)
    WHERE status IN ('ACTIVE', 'PENDING_DOWNGRADE');

CREATE INDEX idx_subs_user ON subscriptions (user_id, status);
CREATE INDEX idx_subs_ends_at ON subscriptions (ends_at) WHERE status IN ('ACTIVE', 'PENDING_DOWNGRADE');

------------------------------------------------------------
-- Subscription event log (append-only)
------------------------------------------------------------
CREATE TABLE subscription_events (
    id                  BIGSERIAL PRIMARY KEY,
    subscription_id     BIGINT NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    type                VARCHAR(64) NOT NULL,            -- CREATED, TIER_UPGRADED, TIER_DOWNGRADE_SCHEDULED, TIER_DOWNGRADE_APPLIED, CANCELLED, EXPIRED, AUTO_PROMOTED, AUTO_PROMOTION_EXPIRED, RENEWED
    payload             JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor               VARCHAR(64),                     -- 'USER', 'SYSTEM', 'ADMIN'
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sub_events_subscription ON subscription_events (subscription_id, created_at DESC);

------------------------------------------------------------
-- Scheduled tier change (used by paid downgrades)
------------------------------------------------------------
CREATE TABLE scheduled_tier_changes (
    id                  BIGSERIAL PRIMARY KEY,
    subscription_id     BIGINT NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    target_tier_id      BIGINT NOT NULL REFERENCES tiers(id),
    apply_at            TIMESTAMPTZ NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING, APPLIED, CANCELLED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sched_tier_pending ON scheduled_tier_changes (apply_at) WHERE status = 'PENDING';
-- At most one PENDING scheduled change per subscription.
CREATE UNIQUE INDEX uniq_sched_tier_pending_per_sub
    ON scheduled_tier_changes (subscription_id) WHERE status = 'PENDING';

------------------------------------------------------------
-- Tier promotions (temporary auto-upgrades, free, expire at period end)
------------------------------------------------------------
CREATE TABLE tier_promotions (
    id                  BIGSERIAL PRIMARY KEY,
    subscription_id     BIGINT NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    promoted_tier_id    BIGINT NOT NULL REFERENCES tiers(id),
    reason              VARCHAR(255) NOT NULL,           -- e.g. "ORDER_COUNT_RULE:rule_id=3"
    valid_from          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until         TIMESTAMPTZ NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, EXPIRED, SUPERSEDED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT promo_period_valid CHECK (valid_until > valid_from)
);

CREATE INDEX idx_promotions_active ON tier_promotions (subscription_id, status, valid_until);

------------------------------------------------------------
-- Tier promotion rules (config-driven)
------------------------------------------------------------
CREATE TABLE tier_promotion_rules (
    id              BIGSERIAL PRIMARY KEY,
    rule_type       VARCHAR(64) NOT NULL,                -- ORDER_COUNT, MONTHLY_ORDER_VALUE, COHORT
    target_tier_id  BIGINT NOT NULL REFERENCES tiers(id),
    config          JSONB NOT NULL,                      -- {"minOrders":5,"windowDays":30}
    priority        INT NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_promo_rules_active ON tier_promotion_rules (active, priority DESC);

------------------------------------------------------------
-- Orders (minimal — drives tier eligibility for the demo)
------------------------------------------------------------
CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount          NUMERIC(19,2) NOT NULL,
    placed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT orders_amount_nonneg CHECK (amount >= 0)
);

CREATE INDEX idx_orders_user_placed ON orders (user_id, placed_at DESC);

------------------------------------------------------------
-- Idempotency keys (mutating endpoints + charges)
------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    key             VARCHAR(128) NOT NULL,
    scope           VARCHAR(64) NOT NULL,                -- e.g. SUBSCRIBE, CHANGE_TIER, CANCEL, CHARGE
    user_id         BIGINT REFERENCES users(id) ON DELETE CASCADE,
    request_hash    VARCHAR(128) NOT NULL,
    response_status INT,
    response_body   TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT idem_key_unique UNIQUE (scope, key)
);

CREATE INDEX idx_idem_expiry ON idempotency_keys (expires_at);

------------------------------------------------------------
-- updated_at triggers
------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE t TEXT;
BEGIN
    FOR t IN
        SELECT unnest(ARRAY[
            'users','wallets','plans','tiers','benefits','tier_benefits',
            'subscriptions','scheduled_tier_changes','tier_promotions','tier_promotion_rules'
        ])
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%I_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION set_updated_at();', t, t);
    END LOOP;
END$$;
