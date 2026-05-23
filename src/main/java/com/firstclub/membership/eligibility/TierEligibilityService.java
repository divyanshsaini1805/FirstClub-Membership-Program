package com.firstclub.membership.eligibility;

import com.firstclub.membership.order.OrderRepository;
import com.firstclub.membership.subscription.SnapshotInvalidatedEvent;
import com.firstclub.membership.subscription.Subscription;
import com.firstclub.membership.subscription.SubscriptionEvent;
import com.firstclub.membership.subscription.SubscriptionEventRepository;
import com.firstclub.membership.subscription.SubscriptionRepository;
import com.firstclub.membership.tier.Tier;
import com.firstclub.membership.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides whether a user's tier should be auto-promoted, and writes the
 * (temporary, free) {@link TierPromotion} row when it should.
 *
 * Rules of engagement:
 *   - Only promotes upward (never auto-demotes; users keep what they earned).
 *   - Picks the highest-rank tier whose rules match.
 *   - Promotion is bounded by the subscription's current period (ends_at).
 *   - Re-running this method is safe: it supersedes any existing promotion
 *     before inserting a new one.
 *
 * For concurrency, the caller is expected to hold a per-user Redis lock
 * (Phase 9). The DB also guarantees correctness via @Version on Subscription
 * and the unique-active-promotion supersede pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TierEligibilityService {

    private final SubscriptionRepository subscriptions;
    private final TierPromotionRepository promotions;
    private final TierPromotionRuleRepository rules;
    private final SubscriptionEventRepository events;
    private final OrderRepository orders;
    private final UserRepository users;
    private final List<PromotionRuleEvaluator> evaluators;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TierPromotion> reevaluate(Long userId) {
        Subscription sub = subscriptions.findCurrentByUserId(userId).orElse(null);
        if (sub == null) {
            log.debug("Skipping eligibility eval for user={} — no active subscription", userId);
            return Optional.empty();
        }

        Map<String, PromotionRuleEvaluator> byType = evaluators.stream()
                .collect(Collectors.toUnmodifiableMap(PromotionRuleEvaluator::ruleType,
                        Function.identity()));

        var ctx = new Ctx(clock.instant(), orders, users);
        List<TierPromotionRule> allRules = rules.findAllActiveWithTier();

        // Find every rule that matches; pick the one whose target tier has
        // the highest rank.
        Optional<TierPromotionRule> winner = allRules.stream()
                .filter(r -> {
                    PromotionRuleEvaluator e = byType.get(r.getRuleType());
                    if (e == null) {
                        log.warn("Unknown rule type {} — skipping rule id={}", r.getRuleType(), r.getId());
                        return false;
                    }
                    return e.matches(userId, r, ctx);
                })
                .max(Comparator.comparingInt(r -> r.getTargetTier().getRank()));

        if (winner.isEmpty()) {
            // Nothing applies — supersede any active promotion so the snapshot
            // returns to purchased tier.
            int n = supersedeActive(sub.getId());
            if (n > 0) {
                log.info("Cleared {} active promotion(s) for sub={}", n, sub.getId());
                events.save(SubscriptionEvent.builder()
                        .subscriptionId(sub.getId())
                        .type(SubscriptionEvent.Type.AUTO_PROMOTION_EXPIRED)
                        .actor(SubscriptionEvent.Actor.SYSTEM)
                        .payload(Map.of("reason", "no matching rule"))
                        .build());
                publisher.publishEvent(new SnapshotInvalidatedEvent(userId));
            }
            return Optional.empty();
        }

        Tier target = winner.get().getTargetTier();
        Tier purchased = sub.getPurchasedTier();
        if (target.getRank() <= purchased.getRank()) {
            // User already paid for a tier at-or-above what the rule grants —
            // nothing to do.
            return Optional.empty();
        }

        // Skip if the active promotion is already this tier (idempotent re-eval).
        Instant now = clock.instant();
        Optional<TierPromotion> current = promotions.findActiveBySubscriptionId(sub.getId(), now);
        if (current.isPresent() && current.get().getPromotedTier().getId().equals(target.getId())) {
            return current;
        }

        // Supersede the prior (lower) promotion if any.
        supersedeActive(sub.getId());

        TierPromotion fresh = promotions.save(TierPromotion.builder()
                .subscriptionId(sub.getId())
                .promotedTier(target)
                .reason("%s:rule_id=%d".formatted(winner.get().getRuleType(), winner.get().getId()))
                .validFrom(now)
                .validUntil(sub.getEndsAt())
                .status(TierPromotion.Status.ACTIVE)
                .build());

        events.save(SubscriptionEvent.builder()
                .subscriptionId(sub.getId())
                .type(SubscriptionEvent.Type.AUTO_PROMOTED)
                .actor(SubscriptionEvent.Actor.SYSTEM)
                .payload(Map.of(
                        "fromTier", purchased.getCode(),
                        "toTier", target.getCode(),
                        "reason", fresh.getReason(),
                        "validUntil", fresh.getValidUntil().toString()))
                .build());

        log.info("Auto-promoted user={} sub={} {} → {} (reason={})",
                userId, sub.getId(), purchased.getCode(), target.getCode(), fresh.getReason());
        publisher.publishEvent(new SnapshotInvalidatedEvent(userId));
        return Optional.of(fresh);
    }

    private int supersedeActive(Long subscriptionId) {
        var actives = promotions.findBySubscriptionIdAndStatus(subscriptionId, TierPromotion.Status.ACTIVE);
        for (TierPromotion p : actives) {
            p.setStatus(TierPromotion.Status.SUPERSEDED);
        }
        promotions.saveAll(actives);
        return actives.size();
    }

    private record Ctx(Instant now, OrderRepository orders, UserRepository users)
            implements PromotionRuleEvaluator.EvaluationContext {}
}
