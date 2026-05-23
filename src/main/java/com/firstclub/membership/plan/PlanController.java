package com.firstclub.membership.plan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "Plans", description = "Membership billing cadences (Monthly / Quarterly / Yearly)")
public class PlanController {

    private final PlanService plans;

    @Operation(summary = "List all active plans, ordered by duration")
    @GetMapping
    public List<PlanDto> list() {
        return plans.listActive();
    }
}
