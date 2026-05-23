package com.firstclub.membership.tier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tiers")
@RequiredArgsConstructor
@Tag(name = "Tiers", description = "Membership tiers and their benefits")
public class TierController {

    private final TierService tiers;

    @Operation(summary = "List all active tiers with their benefits (ordered by rank)")
    @GetMapping
    public List<TierDto.TierView> list() {
        return tiers.listActiveWithBenefits();
    }
}
