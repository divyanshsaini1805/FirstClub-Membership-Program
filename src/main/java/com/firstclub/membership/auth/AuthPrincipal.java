package com.firstclub.membership.auth;

/**
 * Slim, immutable representation of the authenticated user that gets attached
 * to the SecurityContext. Avoids dragging the User JPA entity into the web layer.
 */
public record AuthPrincipal(Long userId, String email) {}
