package com.firstclub.membership.eligibility;

/**
 * Strategy for one *type* of promotion rule (e.g. ORDER_COUNT). Each impl is
 * registered as a Spring bean and discovered by {@link #ruleType()}.
 *
 * Adding a new rule type = new bean implementing this interface + a new
 * tier_promotion_rule row. No schema or core-engine change needed.
 */
public interface PromotionRuleEvaluator {

    /** Matches the {@code rule_type} column in {@code tier_promotion_rules}. */
    String ruleType();

    /**
     * @return true when the {@code rule} applies to the given user.
     */
    boolean matches(Long userId, TierPromotionRule rule, EvaluationContext ctx);

    /**
     * Carries everything strategies need — repos, clock — so they stay
     * test-friendly and free of bean injection.
     */
    interface EvaluationContext {
        java.time.Instant now();
        com.firstclub.membership.order.OrderRepository orders();
        com.firstclub.membership.user.UserRepository users();
    }
}
