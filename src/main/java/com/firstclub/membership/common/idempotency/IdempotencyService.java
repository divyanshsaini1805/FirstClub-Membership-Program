package com.firstclub.membership.common.idempotency;

import com.firstclub.membership.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Two-step idempotency for mutating endpoints:
 *
 *   1. {@link #reserve(String, String, Long, String)} — try to insert a row;
 *      on conflict the operation is a retry. Stored response is replayed.
 *   2. {@link #recordResult(IdempotencyKey, int, String)} — write the
 *      response so future retries replay it verbatim.
 *
 * Postgres is the source of truth (durability + unique index); Redis is a
 * later optimization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repo;
    private final AppProperties props;
    private final Clock clock;

    /**
     * Reserves the (scope, key) pair.
     *  - Returns {@code fresh} if no prior reservation exists — caller should
     *    proceed and call {@link #recordResult}.
     *  - Returns {@code replay} if a prior reservation exists. If that prior
     *    request hadn't recorded a result yet, the caller treats it as an
     *    in-flight duplicate and rejects with 409.
     *
     * Each invocation runs in its own transaction (REQUIRES_NEW) so the
     * reservation row is committed before the business work begins.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReserveOutcome reserve(String scope, String key, Long userId, String requestHash) {
        if (key == null || key.isBlank()) {
            return ReserveOutcome.fresh(null);
        }
        // Check first to avoid hibernate session contamination on unique violation.
        // Unique constraint is the backstop for the rare check-then-insert race.
        Optional<IdempotencyKey> existing = repo.findByScopeAndKey(scope, key);
        if (existing.isPresent()) {
            return ReserveOutcome.replay(existing.get());
        }
        try {
            IdempotencyKey saved = repo.saveAndFlush(IdempotencyKey.builder()
                    .scope(scope)
                    .key(key)
                    .userId(userId)
                    .requestHash(requestHash)
                    .expiresAt(clock.instant().plus(props.idempotency().ttl()))
                    .build());
            return ReserveOutcome.fresh(saved);
        } catch (DataIntegrityViolationException dup) {
            // Race: another request inserted the same (scope,key) between our
            // check and insert. Rare but real under heavy retry. Surface as a
            // conflict — the duplicate may still be in-flight.
            throw new InFlightDuplicateException(scope, key);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(IdempotencyKey reservation, int status, String body) {
        if (reservation == null) return;
        reservation.setResponseStatus(status);
        reservation.setResponseBody(body);
        repo.save(reservation);
    }

    /**
     * @param reservation null if the request had no idempotency key — recording is a no-op.
     * @param replay  the prior response to return, or null if this is a fresh request.
     */
    public record ReserveOutcome(IdempotencyKey reservation, IdempotencyKey replay) {
        static ReserveOutcome fresh(IdempotencyKey r) { return new ReserveOutcome(r, null); }
        static ReserveOutcome replay(IdempotencyKey r) { return new ReserveOutcome(null, r); }
        public boolean isReplay() { return replay != null; }
    }
}
