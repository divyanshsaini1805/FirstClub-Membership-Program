package com.firstclub.membership.subscription;

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

@Getter
@Setter
@Entity
@Table(name = "scheduled_tier_changes")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledTierChange extends BaseEntity {

    public enum Status { PENDING, APPLIED, CANCELLED }

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_tier_id", nullable = false)
    private Tier targetTier;

    @Column(name = "apply_at", nullable = false)
    private Instant applyAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;
}
