package com.firstclub.membership.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * Persists the order, then publishes an {@link OrderPlacedEvent}. Eligibility
 * re-evaluation runs as a separate AFTER_COMMIT listener so the order tx
 * isn't blocked by external locks or longer DB work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public Order placeOrder(Long userId, BigDecimal amount) {
        Order saved = orders.save(Order.builder()
                .userId(userId)
                .amount(amount)
                .placedAt(clock.instant())
                .build());
        events.publishEvent(new OrderPlacedEvent(
                saved.getId(), userId, saved.getAmount(), saved.getPlacedAt()));
        log.info("Order id={} placed for user={} amount={}", saved.getId(), userId, amount);
        return saved;
    }
}
