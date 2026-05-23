-- Seed: plans, tiers, benefits, tier_benefits, promotion rules.
-- Idempotent: ON CONFLICT clauses so reruns don't fail.

INSERT INTO plans (code, name, description, duration_days, base_price) VALUES
    ('MONTHLY',   'Monthly Membership',   'Renews every month',     30,  199.00),
    ('QUARTERLY', 'Quarterly Membership', 'Renews every 3 months',  90,  499.00),
    ('YEARLY',    'Yearly Membership',    'Renews every year',     365, 1499.00)
ON CONFLICT (code) DO NOTHING;

INSERT INTO tiers (code, name, description, rank, price) VALUES
    ('SILVER',   'Silver',   'Entry tier with the essentials',                1,    0.00),
    ('GOLD',     'Gold',     'Higher discounts and exclusive deals',          2,  500.00),
    ('PLATINUM', 'Platinum', 'Top tier — priority support and best perks',    3, 1500.00)
ON CONFLICT (code) DO NOTHING;

INSERT INTO benefits (code, name, description) VALUES
    ('FREE_DELIVERY',     'Free Delivery',     'Free delivery on eligible orders'),
    ('PERCENT_DISCOUNT',  'Extra Discount',    'Extra percentage discount on selected categories'),
    ('EARLY_ACCESS',      'Early Access',      'Early access to sales and exclusive deals'),
    ('PRIORITY_SUPPORT',  'Priority Support',  'Priority customer support')
ON CONFLICT (code) DO NOTHING;

-- Tier-to-benefit bindings with per-tier JSONB config.
INSERT INTO tier_benefits (tier_id, benefit_id, config)
SELECT t.id, b.id, c.config::jsonb
FROM (VALUES
    ('SILVER',   'FREE_DELIVERY',    '{"minOrderValue": 499}'),
    ('SILVER',   'PERCENT_DISCOUNT', '{"percent": 2,  "categories": ["GROCERY"]}'),
    ('GOLD',     'FREE_DELIVERY',    '{"minOrderValue": 199}'),
    ('GOLD',     'PERCENT_DISCOUNT', '{"percent": 5,  "categories": ["GROCERY","ELECTRONICS"]}'),
    ('GOLD',     'EARLY_ACCESS',     '{"hoursBefore": 24}'),
    ('PLATINUM', 'FREE_DELIVERY',    '{"minOrderValue": 0}'),
    ('PLATINUM', 'PERCENT_DISCOUNT', '{"percent": 10, "categories": ["GROCERY","ELECTRONICS","FASHION"]}'),
    ('PLATINUM', 'EARLY_ACCESS',     '{"hoursBefore": 48}'),
    ('PLATINUM', 'PRIORITY_SUPPORT', '{"channel": "phone", "slaMinutes": 15}')
) AS c(tier_code, benefit_code, config)
JOIN tiers t    ON t.code    = c.tier_code
JOIN benefits b ON b.code    = c.benefit_code
ON CONFLICT (tier_id, benefit_id) DO NOTHING;

-- Auto-promotion rules.
INSERT INTO tier_promotion_rules (rule_type, target_tier_id, config, priority)
SELECT r.rule_type, t.id, r.config::jsonb, r.priority
FROM (VALUES
    ('ORDER_COUNT',          'GOLD',     '{"minOrders": 5,  "windowDays": 30}', 10),
    ('MONTHLY_ORDER_VALUE',  'PLATINUM', '{"minValue": 20000}',                  20),
    ('COHORT',               'PLATINUM', '{"cohort": "VIP_BETA"}',               30)
) AS r(rule_type, tier_code, config, priority)
JOIN tiers t ON t.code = r.tier_code;
