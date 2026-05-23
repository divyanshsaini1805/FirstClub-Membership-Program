package com.firstclub.membership.subscription;

/**
 * Fired when something that affects the user's membership snapshot has
 * changed (subscribe, cancel, tier change, promotion). Picked up by
 * {@link SnapshotInvalidationListener}.
 */
public record SnapshotInvalidatedEvent(Long userId) {}
