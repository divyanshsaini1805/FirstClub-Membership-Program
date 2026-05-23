package com.firstclub.membership.subscription;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Query("""
            select s from Subscription s
              join fetch s.plan
              join fetch s.purchasedTier
             where s.userId = :userId
               and s.status in (
                   com.firstclub.membership.subscription.SubscriptionStatus.ACTIVE,
                   com.firstclub.membership.subscription.SubscriptionStatus.PENDING_DOWNGRADE)
            """)
    Optional<Subscription> findCurrentByUserId(@Param("userId") Long userId);

    @Query("""
            select s from Subscription s
              join fetch s.plan
              join fetch s.purchasedTier
             where s.id = :id
            """)
    Optional<Subscription> findByIdWithRefs(@Param("id") Long id);

    /**
     * Same as {@link #findCurrentByUserId} but acquires a row-level lock —
     * used by lifecycle mutations to serialize concurrent change-tier/cancel
     * within a single transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Subscription s
             where s.userId = :userId
               and s.status in (
                   com.firstclub.membership.subscription.SubscriptionStatus.ACTIVE,
                   com.firstclub.membership.subscription.SubscriptionStatus.PENDING_DOWNGRADE)
            """)
    Optional<Subscription> lockCurrentByUserId(@Param("userId") Long userId);

    @Query("""
            select s from Subscription s
             where s.status in (
                   com.firstclub.membership.subscription.SubscriptionStatus.ACTIVE,
                   com.firstclub.membership.subscription.SubscriptionStatus.PENDING_DOWNGRADE)
               and s.endsAt <= :cutoff
            """)
    List<Subscription> findExpiringBy(@Param("cutoff") Instant cutoff);
}
