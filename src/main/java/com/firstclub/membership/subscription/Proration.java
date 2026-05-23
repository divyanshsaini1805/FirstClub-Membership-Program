package com.firstclub.membership.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Proration math kept in one place. The formula:
 *   prorated = (newTierPrice - oldTierPrice) * (remainingSeconds / totalSeconds)
 * Rounded HALF_UP to 2dp. Returns ZERO if the remaining window is non-positive.
 */
public final class Proration {

    private Proration() {}

    public static BigDecimal computeUpgradeCharge(BigDecimal oldTierPrice,
                                                  BigDecimal newTierPrice,
                                                  Instant now,
                                                  Instant periodStart,
                                                  Instant periodEnd) {
        BigDecimal delta = newTierPrice.subtract(oldTierPrice);
        if (delta.signum() <= 0) {
            return BigDecimal.ZERO; // not an upgrade — caller shouldn't have invoked us.
        }
        long total = Duration.between(periodStart, periodEnd).getSeconds();
        long remaining = Duration.between(now, periodEnd).getSeconds();
        if (remaining <= 0 || total <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = BigDecimal.valueOf(remaining)
                .divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_UP);
        return delta.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
    }
}
