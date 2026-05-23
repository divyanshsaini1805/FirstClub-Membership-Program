package com.firstclub.membership.order;

import com.firstclub.membership.common.persistence.AppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Minimal order stub — exists only to feed the eligibility engine for the demo.
 * A real system would model line items, status, etc. — out of scope here.
 */
@Getter
@Setter
@Entity
@Table(name = "orders")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends AppendOnlyEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;
}
