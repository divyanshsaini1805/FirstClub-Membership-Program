package com.firstclub.membership.subscription;

import com.firstclub.membership.billing.BillingService;
import com.firstclub.membership.billing.PaymentMethod;
import com.firstclub.membership.common.error.Errors;
import com.firstclub.membership.plan.Plan;
import com.firstclub.membership.plan.PlanService;
import com.firstclub.membership.tier.Tier;
import com.firstclub.membership.tier.TierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single point of mutation for {@link Subscription}. Every state change:
 *   1. Loads the subscription under a pessimistic lock (or creates it).
 *   2. Performs the business action.
 *   3. Charges/refunds via {@link BillingService} where applicable, idempotently.
 *   4. Appends a {@link SubscriptionEvent} in the same transaction.
 *
 * Concurrency: pessimistic row lock + @Version on the subscription. Wallet
 * mutations carry their own idempotency keys. The whole flow is wrapped in
 * one @Transactional method so DB and ledger commit together or not at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleService {

    private static final String REF_TYPE_SUBSCRIBE = "SUBSCRIPTION_CREATE";
    private static final String REF_TYPE_TIER_UPGRADE = "TIER_UPGRADE";

    private final SubscriptionRepository subscriptions;
    private final SubscriptionEventRepository events;
    private final ScheduledTierChangeRepository scheduledChanges;
    private final PlanService planService;
    private final TierService tierService;
    private final BillingService billing;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Transactional
    public Subscription subscribe(Long userId, Long planId, Long tierId, String idempotencyKey) {
        if (subscriptions.findCurrentByUserId(userId).isPresent()) {
            throw Errors.conflict("User already has an active subscription");
        }
        Plan plan = planService.requireActiveById(planId);
        Tier tier = tierService.requireActiveById(tierId);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant ends = now.plus(plan.getDurationDays(), ChronoUnit.DAYS);
        BigDecimal price = plan.getBasePrice().add(tier.getPrice());

        Subscription sub = subscriptions.save(Subscription.builder()
                .userId(userId)
                .plan(plan)
                .purchasedTier(tier)
                .status(SubscriptionStatus.ACTIVE)
                .startsAt(now)
                .endsAt(ends)
                .autoRenew(false)
                .build());

        BillingService.BillingResult charge = billing.charge(userId, price,
                new PaymentMethod.ChargeContext(
                        REF_TYPE_SUBSCRIBE,
                        sub.getId().toString(),
                        idempotencyKey != null ? idempotencyKey : "sub-create-" + sub.getId(),
                        "Subscription %d: %s/%s".formatted(sub.getId(), plan.getCode(), tier.getCode())));

        Map<String, Object> payload = orderedMap(
                "planCode", plan.getCode(),
                "tierCode", tier.getCode(),
                "amountCharged", price,
                "billingMethod", charge.methodCode(),
                "billingReference", charge.reference(),
                "endsAt", ends.toString());
        events.save(SubscriptionEvent.builder()
                .subscriptionId(sub.getId())
                .type(SubscriptionEvent.Type.CREATED)
                .actor(SubscriptionEvent.Actor.USER)
                .payload(payload)
                .build());

        log.info("Subscribed user={} subId={} plan={} tier={} price={}",
                userId, sub.getId(), plan.getCode(), tier.getCode(), price);
        publisher.publishEvent(new SnapshotInvalidatedEvent(userId));
        return sub;
    }

    /**
     * Either upgrades the tier immediately (with prorated charge) or schedules
     * a downgrade for period-end. Idempotency-Key, if provided, is the
     * charge's key so retries don't double-debit on upgrade.
     */
    @Transactional
    public ChangeTierOutcome changeTier(Long userId, Long subscriptionId, Long targetTierId,
                                        String idempotencyKey) {
        // Lock the user's active row so two concurrent change-tier calls serialize.
        Subscription sub = subscriptions.lockCurrentByUserId(userId)
                .orElseThrow(() -> Errors.notFound("ActiveSubscription", userId));
        if (!sub.getId().equals(subscriptionId)) {
            throw Errors.notFound("Subscription", subscriptionId);
        }
        if (sub.getStatus() == SubscriptionStatus.CANCELLED
                || sub.getStatus() == SubscriptionStatus.EXPIRED) {
            throw Errors.unprocessable("SUBSCRIPTION_INACTIVE",
                    "Subscription is %s — tier cannot be changed".formatted(sub.getStatus()));
        }

        Tier target = tierService.requireActiveById(targetTierId);
        Tier current = sub.getPurchasedTier();

        // If a downgrade is pending and the user picks back the current tier
        // (or higher), cancel it. Treat as: undo the pending change.
        if (sub.getStatus() == SubscriptionStatus.PENDING_DOWNGRADE
                && target.getRank() >= current.getRank()) {
            scheduledChanges
                    .findBySubscriptionIdAndStatus(sub.getId(), ScheduledTierChange.Status.PENDING)
                    .ifPresent(s -> {
                        s.setStatus(ScheduledTierChange.Status.CANCELLED);
                        scheduledChanges.save(s);
                    });
            if (target.getId().equals(current.getId())) {
                sub.setStatus(SubscriptionStatus.ACTIVE);
                subscriptions.save(sub);
                events.save(SubscriptionEvent.builder()
                        .subscriptionId(sub.getId())
                        .type(SubscriptionEvent.Type.TIER_UPGRADED) // reuse — payload distinguishes
                        .actor(SubscriptionEvent.Actor.USER)
                        .payload(orderedMap("note", "Pending downgrade cancelled", "tier", current.getCode()))
                        .build());
                publisher.publishEvent(new SnapshotInvalidatedEvent(sub.getUserId()));
                return new ChangeTierOutcome(ChangeKind.UPGRADED_IMMEDIATELY, sub.getId(),
                        BigDecimal.ZERO, null, null);
            }
            // else fall through to applyUpgrade (target > current)
        }

        if (current.getId().equals(target.getId())) {
            return new ChangeTierOutcome(ChangeKind.NOOP, sub.getId(), null, null, null);
        }

        if (target.getRank() > current.getRank()) {
            return applyUpgrade(sub, current, target, idempotencyKey);
        } else {
            return scheduleDowngrade(sub, current, target);
        }
    }

    private ChangeTierOutcome applyUpgrade(Subscription sub, Tier current, Tier target, String idempotencyKey) {
        BigDecimal charge = Proration.computeUpgradeCharge(
                current.getPrice(), target.getPrice(),
                clock.instant(), sub.getStartsAt(), sub.getEndsAt());

        BillingService.BillingResult result = null;
        if (charge.signum() > 0) {
            result = billing.charge(sub.getUserId(), charge,
                    new PaymentMethod.ChargeContext(
                            REF_TYPE_TIER_UPGRADE,
                            sub.getId() + ":" + current.getCode() + "->" + target.getCode(),
                            idempotencyKey != null ? idempotencyKey
                                    : "tier-up-" + sub.getId() + "-" + target.getId(),
                            "Upgrade %s → %s (prorated)".formatted(current.getCode(), target.getCode())));
        }

        sub.setPurchasedTier(target);
        // If there was a pending downgrade, the upgrade cancels it.
        scheduledChanges
                .findBySubscriptionIdAndStatus(sub.getId(), ScheduledTierChange.Status.PENDING)
                .ifPresent(s -> {
                    s.setStatus(ScheduledTierChange.Status.CANCELLED);
                    scheduledChanges.save(s);
                });
        if (sub.getStatus() == SubscriptionStatus.PENDING_DOWNGRADE) {
            sub.setStatus(SubscriptionStatus.ACTIVE);
        }
        subscriptions.save(sub);

        events.save(SubscriptionEvent.builder()
                .subscriptionId(sub.getId())
                .type(SubscriptionEvent.Type.TIER_UPGRADED)
                .actor(SubscriptionEvent.Actor.USER)
                .payload(orderedMap(
                        "fromTier", current.getCode(),
                        "toTier", target.getCode(),
                        "amountCharged", charge,
                        "billingMethod", result == null ? null : result.methodCode(),
                        "billingReference", result == null ? null : result.reference()))
                .build());

        log.info("Tier upgrade subId={} {} → {} charge={}",
                sub.getId(), current.getCode(), target.getCode(), charge);
        publisher.publishEvent(new SnapshotInvalidatedEvent(sub.getUserId()));
        return new ChangeTierOutcome(ChangeKind.UPGRADED_IMMEDIATELY, sub.getId(), charge, null, null);
    }

    private ChangeTierOutcome scheduleDowngrade(Subscription sub, Tier current, Tier target) {
        // Replace any prior pending downgrade with the new target.
        scheduledChanges
                .findBySubscriptionIdAndStatus(sub.getId(), ScheduledTierChange.Status.PENDING)
                .ifPresent(s -> {
                    s.setStatus(ScheduledTierChange.Status.CANCELLED);
                    scheduledChanges.save(s);
                });

        ScheduledTierChange scheduled = scheduledChanges.save(ScheduledTierChange.builder()
                .subscriptionId(sub.getId())
                .targetTier(target)
                .applyAt(sub.getEndsAt())
                .status(ScheduledTierChange.Status.PENDING)
                .build());
        sub.setStatus(SubscriptionStatus.PENDING_DOWNGRADE);
        subscriptions.save(sub);

        events.save(SubscriptionEvent.builder()
                .subscriptionId(sub.getId())
                .type(SubscriptionEvent.Type.TIER_DOWNGRADE_SCHEDULED)
                .actor(SubscriptionEvent.Actor.USER)
                .payload(orderedMap(
                        "fromTier", current.getCode(),
                        "toTier", target.getCode(),
                        "applyAt", scheduled.getApplyAt().toString()))
                .build());

        log.info("Scheduled downgrade subId={} {} → {} at {}",
                sub.getId(), current.getCode(), target.getCode(), scheduled.getApplyAt());
        publisher.publishEvent(new SnapshotInvalidatedEvent(sub.getUserId()));
        return new ChangeTierOutcome(ChangeKind.DOWNGRADE_SCHEDULED, sub.getId(),
                BigDecimal.ZERO, scheduled.getId(), scheduled.getApplyAt());
    }

    public record ChangeTierOutcome(
            ChangeKind kind,
            Long subscriptionId,
            BigDecimal amountCharged,
            Long scheduledChangeId,
            Instant takesEffectAt
    ) {}

    public enum ChangeKind { UPGRADED_IMMEDIATELY, DOWNGRADE_SCHEDULED, NOOP }

    @Transactional
    public Subscription cancel(Long userId, Long subscriptionId) {
        Subscription sub = subscriptions.findById(subscriptionId)
                .orElseThrow(() -> Errors.notFound("Subscription", subscriptionId));
        if (!sub.getUserId().equals(userId)) {
            // Don't leak existence of another user's subscription.
            throw Errors.notFound("Subscription", subscriptionId);
        }
        if (sub.getStatus() == SubscriptionStatus.CANCELLED
                || sub.getStatus() == SubscriptionStatus.EXPIRED) {
            return sub; // idempotent — repeat call is a no-op.
        }

        // Cancel at period end — keeps it simple: user keeps benefits until ends_at,
        // then the expiry sweeper flips status to EXPIRED. We mark cancelled_at now
        // so it's visible to the user and audited.
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        subscriptions.save(sub);

        events.save(SubscriptionEvent.builder()
                .subscriptionId(sub.getId())
                .type(SubscriptionEvent.Type.CANCELLED)
                .actor(SubscriptionEvent.Actor.USER)
                .payload(orderedMap(
                        "cancelledAt", sub.getCancelledAt().toString(),
                        "accessUntil", sub.getEndsAt().toString(),
                        "refunded", false))
                .build());

        log.info("Cancelled subscription id={} for user={} (access retained until {})",
                sub.getId(), userId, sub.getEndsAt());
        publisher.publishEvent(new SnapshotInvalidatedEvent(userId));
        return sub;
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("k/v pairs expected");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
