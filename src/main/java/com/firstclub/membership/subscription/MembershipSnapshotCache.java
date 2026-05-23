package com.firstclub.membership.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed cache of the per-user membership snapshot. Hits skip a couple
 * of DB round-trips (subscription + active promotion + tier benefits).
 * Invalidated on every subscription mutation via {@link #invalidate(Long)}.
 *
 * Uses Redisson directly — same client that backs distributed locks — so we
 * don't depend on Spring Data Redis auto-config quirks.
 *
 * Cache failures are non-fatal: we log and fall through to the DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipSnapshotCache {

    private static final String KEY_PREFIX = "membership:snapshot:user:";

    private final RedissonClient redisson;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public Optional<SubscriptionDto.SubscriptionView> get(Long userId) {
        try {
            RBucket<String> bucket = redisson.getBucket(key(userId));
            String json = bucket.get();
            log.debug("Snapshot cache get user={} → {}", userId, json == null ? "MISS" : "HIT");
            if (json == null) return Optional.empty();
            return Optional.of(mapper.readValue(json, SubscriptionDto.SubscriptionView.class));
        } catch (Exception ex) {
            log.warn("Snapshot cache get failed for user={}: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(Long userId, SubscriptionDto.SubscriptionView snapshot) {
        try {
            String json = mapper.writeValueAsString(snapshot);
            RBucket<String> bucket = redisson.getBucket(key(userId));
            bucket.set(json, props.cache().membershipSnapshotTtl().toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Snapshot cache put user={} bytes={}", userId, json.length());
        } catch (Exception ex) {
            log.warn("Snapshot cache put failed for user={}: {}", userId, ex.getMessage());
        }
    }

    public void invalidate(Long userId) {
        try {
            redisson.getBucket(key(userId)).delete();
        } catch (Exception ex) {
            log.warn("Snapshot cache invalidate failed for user={}: {}", userId, ex.getMessage());
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
