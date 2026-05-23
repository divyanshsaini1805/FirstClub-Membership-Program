package com.firstclub.membership.eligibility;

import com.firstclub.membership.order.OrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Triggers tier eligibility re-evaluation after an order commits. Runs in
 * AFTER_COMMIT so the order's transaction is closed before the lock is taken
 * and the snapshot is updated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPlacedEligibilityListener {

    private final EligibilityCoordinator coordinator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        try {
            coordinator.reevaluate(event.userId());
        } catch (Exception ex) {
            // Don't fail the original transaction on a downstream eval error —
            // a future order or the nightly sweeper will catch up.
            log.warn("Tier reevaluation failed for user={} after order={}", event.userId(), event.orderId(), ex);
        }
    }
}
