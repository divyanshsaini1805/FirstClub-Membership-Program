package com.firstclub.membership.subscription;

import com.firstclub.membership.tier.TierDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public final class SubscriptionDto {
    private SubscriptionDto() {}

    public record SubscribeRequest(
            @NotNull Long planId,
            @NotNull Long tierId
    ) {}

    public record ChangeTierRequest(
            @NotNull Long targetTierId
    ) {}

    public record ChangeTierResult(
            ChangeKind kind,
            SubscriptionView snapshot,
            BigDecimal amountCharged,
            Long scheduledChangeId,
            Instant takesEffectAt
    ) {
        public enum ChangeKind { UPGRADED_IMMEDIATELY, DOWNGRADE_SCHEDULED, NOOP }
    }

    public record SubscriptionView(
            Long id,
            Long userId,
            PlanSummary plan,
            TierSummary purchasedTier,
            @Schema(description = "Tier currently in effect — may be higher than purchasedTier due to an active promotion")
            TierSummary effectiveTier,
            ActivePromotion activePromotion,
            SubscriptionStatus status,
            Instant startsAt,
            Instant endsAt,
            Instant cancelledAt,
            BigDecimal amountCharged,
            TierDto.TierView effectiveTierBenefits
    ) {}

    public record PlanSummary(Long id, String code, String name, Integer durationDays, BigDecimal basePrice) {}

    public record TierSummary(Long id, String code, String name, Integer rank, BigDecimal price) {}

    public record ActivePromotion(Long id, String reason, Instant validUntil) {}
}
