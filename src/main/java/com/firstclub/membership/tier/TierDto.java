package com.firstclub.membership.tier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class TierDto {
    private TierDto() {}

    public record TierView(
            Long id,
            String code,
            String name,
            String description,
            Integer rank,
            BigDecimal price,
            List<BenefitView> benefits
    ) {}

    public record BenefitView(
            String code,
            String name,
            Map<String, Object> config
    ) {}
}
