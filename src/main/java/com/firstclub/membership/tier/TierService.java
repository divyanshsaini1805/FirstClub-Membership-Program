package com.firstclub.membership.tier;

import com.firstclub.membership.common.error.Errors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TierService {

    private final TierRepository tiers;
    private final TierBenefitRepository tierBenefits;

    @Transactional(readOnly = true)
    public List<TierDto.TierView> listActiveWithBenefits() {
        List<Tier> activeTiers = tiers.findByActiveTrueOrderByRankAsc();
        Map<Long, List<TierDto.BenefitView>> benefitsByTier = new LinkedHashMap<>();
        for (TierBenefit tb : tierBenefits.findAllActiveWithRefs()) {
            benefitsByTier.computeIfAbsent(tb.getTier().getId(), k -> new ArrayList<>())
                    .add(new TierDto.BenefitView(
                            tb.getBenefit().getCode(),
                            tb.getBenefit().getName(),
                            tb.getConfig()));
        }
        return activeTiers.stream()
                .map(t -> new TierDto.TierView(
                        t.getId(), t.getCode(), t.getName(), t.getDescription(),
                        t.getRank(), t.getPrice(),
                        benefitsByTier.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Tier requireActiveById(Long id) {
        Tier t = tiers.findById(id).orElseThrow(() -> Errors.notFound("Tier", id));
        if (!Boolean.TRUE.equals(t.getActive())) {
            throw Errors.badRequest("TIER_INACTIVE", "Tier %s is not active".formatted(t.getCode()));
        }
        return t;
    }

    @Transactional(readOnly = true)
    public Tier requireActiveByCode(String code) {
        Tier t = tiers.findByCode(code).orElseThrow(() -> Errors.notFound("Tier", code));
        if (!Boolean.TRUE.equals(t.getActive())) {
            throw Errors.badRequest("TIER_INACTIVE", "Tier %s is not active".formatted(code));
        }
        return t;
    }
}
