package com.firstclub.membership.eligibility;

import com.firstclub.membership.auth.AuthPrincipal;
import com.firstclub.membership.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/users/me/eligibility")
@RequiredArgsConstructor
@Tag(name = "Eligibility", description = "Re-evaluate tier eligibility for the authenticated user")
public class EligibilityController {

    private final EligibilityCoordinator coordinator;

    @Operation(summary = "Force a tier eligibility re-evaluation (useful for demos)")
    @PostMapping("/reevaluate")
    public ReevaluateResponse reevaluate(@CurrentUser AuthPrincipal me) {
        Optional<TierPromotion> result = coordinator.reevaluate(me.userId());
        return result
                .map(p -> new ReevaluateResponse(
                        true, p.getPromotedTier().getCode(), p.getReason(), p.getValidUntil()))
                .orElse(new ReevaluateResponse(false, null, null, null));
    }

    public record ReevaluateResponse(boolean promoted, String tierCode, String reason, Instant validUntil) {}
}
