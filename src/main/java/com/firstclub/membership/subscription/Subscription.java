package com.firstclub.membership.subscription;

import com.firstclub.membership.common.persistence.BaseEntity;
import com.firstclub.membership.plan.Plan;
import com.firstclub.membership.tier.Tier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "subscriptions")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    /**
     * The tier the user *paid* for. The effective tier may be higher due to
     * an active {@code TierPromotion}; see {@code SubscriptionViewService}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchased_tier_id", nullable = false)
    private Tier purchasedTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubscriptionStatus status;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew;

    @Version
    @Column(nullable = false)
    private Long version;

    public boolean isActiveOrPendingDowngrade() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PENDING_DOWNGRADE;
    }
}
