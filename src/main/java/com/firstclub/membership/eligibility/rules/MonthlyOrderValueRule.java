package com.firstclub.membership.eligibility.rules;

import com.firstclub.membership.eligibility.PromotionRuleEvaluator;
import com.firstclub.membership.eligibility.TierPromotionRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * "User's total order value in the current calendar month (UTC) is at least
 * {@code minValue}."
 *
 * Config schema: {@code {"minValue": number}}
 */
@Component
public class MonthlyOrderValueRule implements PromotionRuleEvaluator {

    public static final String TYPE = "MONTHLY_ORDER_VALUE";

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public boolean matches(Long userId, TierPromotionRule rule, EvaluationContext ctx) {
        Map<String, Object> cfg = rule.getConfig();
        BigDecimal threshold = bdOf(cfg.get("minValue"));

        LocalDate today = ctx.now().atZone(ZoneOffset.UTC).toLocalDate();
        var monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        BigDecimal total = ctx.orders().sumByUserSince(userId, monthStart);
        return total != null && total.compareTo(threshold) >= 0;
    }

    private static BigDecimal bdOf(Object o) {
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o instanceof String s) return new BigDecimal(s);
        throw new IllegalArgumentException("Expected number, got: " + o);
    }
}
