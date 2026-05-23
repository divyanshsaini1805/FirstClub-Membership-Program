package com.firstclub.membership.eligibility.rules;

import com.firstclub.membership.eligibility.PromotionRuleEvaluator;
import com.firstclub.membership.eligibility.TierPromotionRule;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * "User has placed at least {@code minOrders} orders in the last
 * {@code windowDays} days."
 *
 * Config schema: {@code {"minOrders": int, "windowDays": int}}
 */
@Component
public class OrderCountRule implements PromotionRuleEvaluator {

    public static final String TYPE = "ORDER_COUNT";

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public boolean matches(Long userId, TierPromotionRule rule, EvaluationContext ctx) {
        Map<String, Object> cfg = rule.getConfig();
        int minOrders = intOf(cfg.get("minOrders"));
        int windowDays = intOf(cfg.get("windowDays"));
        Instant since = ctx.now().minus(windowDays, ChronoUnit.DAYS);
        long count = ctx.orders().countByUserSince(userId, since);
        return count >= minOrders;
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Expected number, got: " + o);
    }
}
