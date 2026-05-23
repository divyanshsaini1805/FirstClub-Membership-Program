package com.firstclub.membership.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodic sweeper that:
 *   - applies scheduled tier downgrades whose {@code applyAt} has passed
 *   - flips ACTIVE/PENDING_DOWNGRADE subs past {@code endsAt} to EXPIRED
 *
 * Idempotent — runs as often as every minute with no harm. In multi-instance
 * deployments, wrap in a distributed lock (Phase 9 Redisson) to avoid duplicate
 * work; for the demo a single instance is fine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionMaintenanceJob {

    private final SubscriptionRepository subscriptions;
    private final ScheduledTierChangeRepository scheduledChanges;
    private final SubscriptionEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT15S")
    @Transactional
    public void run() {
        applyDueDowngrades();
        expirePastDueSubscriptions();
    }

    void applyDueDowngrades() {
        Instant now = clock.instant();
        for (ScheduledTierChange change : scheduledChanges.findPendingDueBy(now)) {
            Subscription sub = subscriptions.findById(change.getSubscriptionId()).orElse(null);
            if (sub == null) {
                change.setStatus(ScheduledTierChange.Status.CANCELLED);
                scheduledChanges.save(change);
                continue;
            }
            if (sub.getStatus() == SubscriptionStatus.CANCELLED
                    || sub.getStatus() == SubscriptionStatus.EXPIRED) {
                change.setStatus(ScheduledTierChange.Status.CANCELLED);
                scheduledChanges.save(change);
                continue;
            }
            String fromCode = sub.getPurchasedTier().getCode();
            String toCode = change.getTargetTier().getCode();

            sub.setPurchasedTier(change.getTargetTier());
            sub.setStatus(SubscriptionStatus.ACTIVE);
            subscriptions.save(sub);

            change.setStatus(ScheduledTierChange.Status.APPLIED);
            scheduledChanges.save(change);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fromTier", fromCode);
            payload.put("toTier", toCode);
            payload.put("appliedBy", "SYSTEM");
            events.save(SubscriptionEvent.builder()
                    .subscriptionId(sub.getId())
                    .type(SubscriptionEvent.Type.TIER_DOWNGRADE_APPLIED)
                    .actor(SubscriptionEvent.Actor.SYSTEM)
                    .payload(payload)
                    .build());

            log.info("Applied scheduled downgrade subId={} {} → {}",
                    sub.getId(), fromCode, toCode);
            publisher.publishEvent(new SnapshotInvalidatedEvent(sub.getUserId()));
        }
    }

    void expirePastDueSubscriptions() {
        Instant now = clock.instant();
        for (Subscription sub : subscriptions.findExpiringBy(now)) {
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptions.save(sub);
            events.save(SubscriptionEvent.builder()
                    .subscriptionId(sub.getId())
                    .type(SubscriptionEvent.Type.EXPIRED)
                    .actor(SubscriptionEvent.Actor.SYSTEM)
                    .payload(Map.of("endsAt", sub.getEndsAt().toString()))
                    .build());
            log.info("Expired subscription id={} user={}", sub.getId(), sub.getUserId());
            publisher.publishEvent(new SnapshotInvalidatedEvent(sub.getUserId()));
        }
    }
}
