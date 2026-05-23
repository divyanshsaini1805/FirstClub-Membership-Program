package com.firstclub.membership.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Strongly-typed application config bound from {@code app.*} in application.yml.
 * Validated at startup so misconfiguration fails fast instead of in-flight.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Security security,
        Wallet wallet,
        Subscription subscription,
        Eligibility eligibility,
        Cache cache,
        Idempotency idempotency
) {

    public record Security(Jwt jwt) {
        public record Jwt(
                @NotBlank String secret,
                @NotNull Duration accessTokenTtl,
                @NotBlank String issuer
        ) {}
    }

    public record Wallet(@NotNull @Positive BigDecimal signupBonus) {}

    public record Subscription(boolean autoRenew) {}

    public record Eligibility(Sweeper sweeper) {
        public record Sweeper(boolean enabled, @NotBlank String cron) {}
    }

    public record Cache(
            @NotNull Duration plansTtl,
            @NotNull Duration tiersTtl,
            @NotNull Duration membershipSnapshotTtl
    ) {}

    public record Idempotency(@NotNull Duration ttl) {}
}
