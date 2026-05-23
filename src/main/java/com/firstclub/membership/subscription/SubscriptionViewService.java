package com.firstclub.membership.subscription;

import com.firstclub.membership.common.error.Errors;
import com.firstclub.membership.eligibility.TierPromotion;
import com.firstclub.membership.eligibility.TierPromotionRepository;
import com.firstclub.membership.tier.Tier;
import com.firstclub.membership.tier.TierBenefit;
import com.firstclub.membership.tier.TierBenefitRepository;
import com.firstclub.membership.tier.TierDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Read-side projection of the membership snapshot. Composes:
 *  - the user's current Subscription
 *  - any active TierPromotion (auto-upgrade)
 *  - the tier-benefit catalogue for the effective tier
 *
 * Effective tier = max(purchasedTier.rank, activePromotion.rank). This is
 * computed, never stored, so it can't go stale.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionViewService {

    private final SubscriptionRepository subscriptions;
    private final TierPromotionRepository promotions;
    private final TierBenefitRepository tierBenefits;
    private final MembershipSnapshotCache cache;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SubscriptionDto.SubscriptionView getCurrentSnapshot(Long userId) {
        var cached = cache.get(userId);
        if (cached.isPresent()) return cached.get();
        Subscription sub = subscriptions.findCurrentByUserId(userId)
                .orElseThrow(() -> Errors.notFound("ActiveSubscription", userId));
        var snap = buildSnapshot(sub);
        cache.put(userId, snap);
        return snap;
    }

    @Transactional(readOnly = true)
    public Optional<SubscriptionDto.SubscriptionView> findCurrentSnapshot(Long userId) {
        return subscriptions.findCurrentByUserId(userId).map(this::buildSnapshot);
    }

    /**
     * Re-fetches by id with join-fetched references so callers can pass just
     * an id and not worry about lazy-init across transaction boundaries.
     */
    @Transactional(readOnly = true)
    public SubscriptionDto.SubscriptionView snapshotById(Long subscriptionId) {
        Subscription sub = subscriptions.findByIdWithRefs(subscriptionId)
                .orElseThrow(() -> Errors.notFound("Subscription", subscriptionId));
        return buildSnapshot(sub);
    }

    private SubscriptionDto.SubscriptionView buildSnapshot(Subscription sub) {
        Optional<TierPromotion> active = promotions.findActiveBySubscriptionId(sub.getId(), clock.instant());
        Tier purchased = sub.getPurchasedTier();
        Tier effective = active
                .filter(p -> p.getPromotedTier().getRank() > purchased.getRank())
                .map(TierPromotion::getPromotedTier)
                .orElse(purchased);

        List<TierBenefit> benefits = tierBenefits.findActiveByTierId(effective.getId());

        return new SubscriptionDto.SubscriptionView(
                sub.getId(),
                sub.getUserId(),
                planSummary(sub),
                tierSummary(purchased),
                tierSummary(effective),
                active.filter(p -> effective.equals(p.getPromotedTier()))
                        .map(p -> new SubscriptionDto.ActivePromotion(p.getId(), p.getReason(), p.getValidUntil()))
                        .orElse(null),
                sub.getStatus(),
                sub.getStartsAt(),
                sub.getEndsAt(),
                sub.getCancelledAt(),
                sub.getPlan().getBasePrice().add(purchased.getPrice()),
                new TierDto.TierView(
                        effective.getId(), effective.getCode(), effective.getName(), effective.getDescription(),
                        effective.getRank(), effective.getPrice(),
                        benefits.stream()
                                .map(tb -> new TierDto.BenefitView(
                                        tb.getBenefit().getCode(),
                                        tb.getBenefit().getName(),
                                        tb.getConfig()))
                                .toList())
        );
    }

    private static SubscriptionDto.PlanSummary planSummary(Subscription s) {
        return new SubscriptionDto.PlanSummary(
                s.getPlan().getId(), s.getPlan().getCode(), s.getPlan().getName(),
                s.getPlan().getDurationDays(), s.getPlan().getBasePrice());
    }

    private static SubscriptionDto.TierSummary tierSummary(Tier t) {
        return new SubscriptionDto.TierSummary(t.getId(), t.getCode(), t.getName(), t.getRank(), t.getPrice());
    }
}
