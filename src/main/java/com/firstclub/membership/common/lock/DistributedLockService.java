package com.firstclub.membership.common.lock;

import com.firstclub.membership.common.error.Errors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Thin facade over Redisson locks. Use to serialize work that must not run
 * concurrently for the same resource (e.g. two orders racing on the same
 * user's tier evaluation).
 *
 * Releases the lock in a finally block so a thrown business exception still
 * cleans up. Lock leases also auto-expire — a process crash never permanently
 * blocks the resource.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(15);

    private final RedissonClient redisson;

    public <T> T runLocked(String key, Supplier<T> work) {
        return runLocked(key, DEFAULT_WAIT, DEFAULT_LEASE, work);
    }

    public <T> T runLocked(String key, Duration wait, Duration lease, Supplier<T> work) {
        RLock lock = redisson.getLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw Errors.conflict("Could not acquire lock for " + key + " within " + wait);
            }
            return work.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw Errors.conflict("Interrupted waiting for lock " + key);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ex) {
                    log.warn("Failed to release lock {}: {}", key, ex.getMessage());
                }
            }
        }
    }
}
