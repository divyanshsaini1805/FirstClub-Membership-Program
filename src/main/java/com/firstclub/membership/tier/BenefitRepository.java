package com.firstclub.membership.tier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BenefitRepository extends JpaRepository<Benefit, Long> {
    Optional<Benefit> findByCode(String code);
}
