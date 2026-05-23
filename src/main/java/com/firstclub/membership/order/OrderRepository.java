package com.firstclub.membership.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("""
            select count(o) from Order o
             where o.userId = :userId
               and o.placedAt >= :since
            """)
    long countByUserSince(@Param("userId") Long userId, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(o.amount), 0)
              from Order o
             where o.userId = :userId
               and o.placedAt >= :since
            """)
    BigDecimal sumByUserSince(@Param("userId") Long userId, @Param("since") Instant since);
}
