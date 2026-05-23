package com.firstclub.membership.eligibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TierPromotionRuleRepository extends JpaRepository<TierPromotionRule, Long> {

    @Query("""
            select r from TierPromotionRule r
              join fetch r.targetTier
             where r.active = true
             order by r.priority desc
            """)
    List<TierPromotionRule> findAllActiveWithTier();
}
