package com.firstclub.membership.eligibility;

import com.firstclub.membership.common.lock.DistributedLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Public entry point for triggering a tier eligibility re-evaluation. Wraps
 * the underlying service in a per-user distributed lock so two concurrent
 * "place order" calls for the same user serialize on tier writes.
 *
 * Callers should always go through this — never call
 * {@link TierEligibilityService#reevaluate(Long)} directly outside this class.
 */
@Service
@RequiredArgsConstructor
public class EligibilityCoordinator {

    private static final String LOCK_PREFIX = "lock:tier-eval:user:";

    private final TierEligibilityService eligibility;
    private final DistributedLockService locks;

    public Optional<TierPromotion> reevaluate(Long userId) {
        return locks.runLocked(LOCK_PREFIX + userId, () -> eligibility.reevaluate(userId));
    }
}
