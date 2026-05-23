package com.firstclub.membership.eligibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TierPromotionRepository extends JpaRepository<TierPromotion, Long> {

    @Query("""
            select p from TierPromotion p
              join fetch p.promotedTier
             where p.subscriptionId = :subscriptionId
               and p.status = :active
               and p.validUntil > :now
             order by p.promotedTier.rank desc
            """)
    Optional<TierPromotion> findActiveBySubscriptionId(@Param("subscriptionId") Long subscriptionId,
                                                       @Param("now") Instant now,
                                                       @Param("active") TierPromotion.Status active);

    default Optional<TierPromotion> findActiveBySubscriptionId(Long subscriptionId, Instant now) {
        return findActiveBySubscriptionId(subscriptionId, now, TierPromotion.Status.ACTIVE);
    }

    List<TierPromotion> findBySubscriptionIdAndStatus(Long subscriptionId, TierPromotion.Status status);
}
