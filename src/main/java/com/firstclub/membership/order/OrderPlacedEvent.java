package com.firstclub.membership.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event published when an order is committed. Consumed via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so listeners run
 * outside the order's transaction — no DB locks held while eligibility evaluates.
 */
public record OrderPlacedEvent(Long orderId, Long userId, BigDecimal amount, Instant placedAt) {}
