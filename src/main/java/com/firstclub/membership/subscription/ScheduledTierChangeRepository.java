package com.firstclub.membership.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduledTierChangeRepository extends JpaRepository<ScheduledTierChange, Long> {

    Optional<ScheduledTierChange> findBySubscriptionIdAndStatus(Long subscriptionId,
                                                                ScheduledTierChange.Status status);

    @Query("""
            select s from ScheduledTierChange s
              join fetch s.targetTier
             where s.status = :pending
               and s.applyAt <= :cutoff
            """)
    List<ScheduledTierChange> findPendingDueBy(@Param("cutoff") Instant cutoff,
                                               @Param("pending") ScheduledTierChange.Status pending);

    default List<ScheduledTierChange> findPendingDueBy(Instant cutoff) {
        return findPendingDueBy(cutoff, ScheduledTierChange.Status.PENDING);
    }
}
