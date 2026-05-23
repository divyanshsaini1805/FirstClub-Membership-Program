package com.firstclub.membership.eligibility;

import com.firstclub.membership.common.persistence.BaseEntity;
import com.firstclub.membership.tier.Tier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Temporary, free auto-upgrade granted by the eligibility engine. The
 * {@link #status} flag is the only field ever mutated after creation —
 * everything else is effectively immutable.
 */
@Getter
@Setter
@Entity
@Table(name = "tier_promotions")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TierPromotion extends BaseEntity {

    public enum Status { ACTIVE, EXPIRED, SUPERSEDED }

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promoted_tier_id", nullable = false)
    private Tier promotedTier;

    @Column(nullable = false)
    private String reason;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;
}
