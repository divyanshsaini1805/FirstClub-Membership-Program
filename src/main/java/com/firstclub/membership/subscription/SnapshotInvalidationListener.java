package com.firstclub.membership.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wipes the cached membership snapshot whenever an internal
 * {@link SnapshotInvalidatedEvent} is published. Lifecycle code emits the
 * event explicitly rather than scanning {@code subscription_events} so
 * invalidation is precise and synchronous with the mutation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotInvalidationListener {

    private final MembershipSnapshotCache cache;

    @EventListener
    public void onInvalidate(SnapshotInvalidatedEvent event) {
        cache.invalidate(event.userId());
        log.debug("Invalidated membership snapshot cache for user={}", event.userId());
    }
}
