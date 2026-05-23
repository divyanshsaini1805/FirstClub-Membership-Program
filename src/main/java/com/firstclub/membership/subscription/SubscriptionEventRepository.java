package com.firstclub.membership.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {
    List<SubscriptionEvent> findBySubscriptionIdOrderByIdDesc(Long subscriptionId);
}
