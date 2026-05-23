package com.firstclub.membership;

import com.firstclub.membership.subscription.Proration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test — no Spring context, no Docker. Confirms proration math
 * matches the documented formula.
 */
class ProrationTest {

    @Test
    void fullPeriodRemaining_chargesFullDelta() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plus(30, ChronoUnit.DAYS);
        BigDecimal charge = Proration.computeUpgradeCharge(
                new BigDecimal("0"), new BigDecimal("500.00"),
                start, start, end);
        assertThat(charge).isEqualByComparingTo("500.00");
    }

    @Test
    void halfPeriodRemaining_chargesHalfDelta() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plus(30, ChronoUnit.DAYS);
        Instant now = start.plus(15, ChronoUnit.DAYS);
        BigDecimal charge = Proration.computeUpgradeCharge(
                new BigDecimal("0"), new BigDecimal("500.00"),
                now, start, end);
        assertThat(charge).isEqualByComparingTo("250.00");
    }

    @Test
    void noTimeRemaining_chargesZero() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plus(30, ChronoUnit.DAYS);
        BigDecimal charge = Proration.computeUpgradeCharge(
                new BigDecimal("0"), new BigDecimal("500.00"),
                end, start, end);
        assertThat(charge).isEqualByComparingTo("0.00");
    }

    @Test
    void downgradeOrSamePrice_chargesZero() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plus(30, ChronoUnit.DAYS);
        BigDecimal charge = Proration.computeUpgradeCharge(
                new BigDecimal("500.00"), new BigDecimal("0"),
                start, start, end);
        assertThat(charge).isEqualByComparingTo("0");
    }
}
