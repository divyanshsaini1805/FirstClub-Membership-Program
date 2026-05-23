package com.firstclub.membership.plan;

import com.firstclub.membership.common.error.Errors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository plans;

    @Transactional(readOnly = true)
    public List<PlanDto> listActive() {
        return plans.findByActiveTrueOrderByDurationDaysAsc()
                .stream().map(PlanDto::from).toList();
    }

    @Transactional(readOnly = true)
    public Plan requireActiveById(Long id) {
        Plan p = plans.findById(id).orElseThrow(() -> Errors.notFound("Plan", id));
        if (!Boolean.TRUE.equals(p.getActive())) {
            throw Errors.badRequest("PLAN_INACTIVE", "Plan %s is not active".formatted(p.getCode()));
        }
        return p;
    }
}
