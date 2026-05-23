package com.firstclub.membership.plan;

import java.math.BigDecimal;

public record PlanDto(
        Long id,
        String code,
        String name,
        String description,
        Integer durationDays,
        BigDecimal basePrice
) {
    public static PlanDto from(Plan p) {
        return new PlanDto(p.getId(), p.getCode(), p.getName(), p.getDescription(),
                p.getDurationDays(), p.getBasePrice());
    }
}
