package com.firstclub.membership.tier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TierRepository extends JpaRepository<Tier, Long> {
    Optional<Tier> findByCode(String code);
    List<Tier> findByActiveTrueOrderByRankAsc();
}
