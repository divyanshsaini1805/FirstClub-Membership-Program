package com.firstclub.membership.common.idempotency;

import com.firstclub.membership.common.persistence.AppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Stores the response of an idempotent request so retries return the same
 * result. Postgres is the durable store; Redis caches recent keys for fast
 * lookup (see {@code IdempotencyService} in Phase 9).
 */
@Getter
@Setter
@Entity
@Table(name = "idempotency_keys")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey extends AppendOnlyEntity {

    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(nullable = false, length = 64)
    private String scope;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
