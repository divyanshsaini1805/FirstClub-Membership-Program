package com.firstclub.membership.tier;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TierBenefitRepository extends JpaRepository<TierBenefit, Long> {

    @Query("""
            select tb from TierBenefit tb
              join fetch tb.benefit b
              join fetch tb.tier t
             where tb.active = true
             order by t.rank asc, b.code asc
            """)
    List<TierBenefit> findAllActiveWithRefs();

    @Query("""
            select tb from TierBenefit tb
              join fetch tb.benefit b
             where tb.tier.id = :tierId and tb.active = true
            """)
    List<TierBenefit> findActiveByTierId(Long tierId);
}
