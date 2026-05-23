package com.firstclub.membership.eligibility.rules;

import com.firstclub.membership.eligibility.PromotionRuleEvaluator;
import com.firstclub.membership.eligibility.TierPromotionRule;
import com.firstclub.membership.user.User;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * "User belongs to a named cohort." Cohort is a free-form tag on User.
 *
 * Config schema: {@code {"cohort": "VIP_BETA"}}
 */
@Component
public class CohortRule implements PromotionRuleEvaluator {

    public static final String TYPE = "COHORT";

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public boolean matches(Long userId, TierPromotionRule rule, EvaluationContext ctx) {
        Object cohortCfg = rule.getConfig().get("cohort");
        if (cohortCfg == null) return false;
        return ctx.users().findById(userId)
                .map(User::getCohort)
                .filter(c -> Objects.equals(c, cohortCfg.toString()))
                .isPresent();
    }
}
