package com.firstclub.membership.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.membership.auth.AuthPrincipal;
import com.firstclub.membership.auth.CurrentUser;
import com.firstclub.membership.common.idempotency.IdempotencyKey;
import com.firstclub.membership.common.idempotency.IdempotencyService;
import com.firstclub.membership.common.idempotency.InFlightDuplicateException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Subscribe / view / cancel — operates on the authenticated user")
public class SubscriptionController {

    private static final String SCOPE_SUBSCRIBE = "SUBSCRIBE";
    private static final String SCOPE_CHANGE_TIER = "CHANGE_TIER";

    private final SubscriptionLifecycleService lifecycle;
    private final SubscriptionViewService views;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    @Operation(summary = "Get the authenticated user's current membership snapshot")
    @GetMapping("/membership")
    public SubscriptionDto.SubscriptionView getMembership(@CurrentUser AuthPrincipal me) {
        return views.getCurrentSnapshot(me.userId());
    }

    @Operation(summary = "Subscribe to a plan + initial tier; charges the wallet")
    @PostMapping("/subscriptions")
    public ResponseEntity<?> subscribe(
            @CurrentUser AuthPrincipal me,
            @Valid @RequestBody SubscriptionDto.SubscribeRequest req,
            @Parameter(description = "Optional idempotency key; same key replays the original outcome")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        IdempotencyService.ReserveOutcome res = idempotency.reserve(
                SCOPE_SUBSCRIBE, idempotencyKey, me.userId(), requestHash(req));
        if (res.isReplay()) {
            return replay(res.replay());
        }

        Subscription sub = lifecycle.subscribe(me.userId(), req.planId(), req.tierId(), idempotencyKey);
        SubscriptionDto.SubscriptionView body = views.snapshotById(sub.getId());
        idempotency.recordResult(res.reservation(), HttpStatus.CREATED.value(), serialize(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "Cancel — access retained until period end, then expires")
    @DeleteMapping("/subscriptions/{id}")
    public SubscriptionDto.SubscriptionView cancel(@CurrentUser AuthPrincipal me,
                                                   @PathVariable Long id) {
        Subscription sub = lifecycle.cancel(me.userId(), id);
        return views.snapshotById(sub.getId());
    }

    @Operation(summary = "Change tier — upgrades charge prorated immediately; downgrades schedule for period-end")
    @PostMapping("/subscriptions/{id}/change-tier")
    public ResponseEntity<?> changeTier(
            @CurrentUser AuthPrincipal me,
            @PathVariable Long id,
            @Valid @RequestBody SubscriptionDto.ChangeTierRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        IdempotencyService.ReserveOutcome res = idempotency.reserve(
                SCOPE_CHANGE_TIER, idempotencyKey, me.userId(),
                requestHash(id, req));
        if (res.isReplay()) {
            return replay(res.replay());
        }

        SubscriptionLifecycleService.ChangeTierOutcome out =
                lifecycle.changeTier(me.userId(), id, req.targetTierId(), idempotencyKey);
        SubscriptionDto.SubscriptionView snap = views.snapshotById(out.subscriptionId());
        SubscriptionDto.ChangeTierResult body = new SubscriptionDto.ChangeTierResult(
                SubscriptionDto.ChangeTierResult.ChangeKind.valueOf(out.kind().name()),
                snap,
                out.amountCharged(),
                out.scheduledChangeId(),
                out.takesEffectAt()
        );
        idempotency.recordResult(res.reservation(), HttpStatus.OK.value(), serialize(body));
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> replay(IdempotencyKey key) {
        if (key.getResponseStatus() == null || key.getResponseBody() == null) {
            // Reserved but no result recorded — original request still in flight or crashed.
            throw new InFlightDuplicateException(key.getScope(), key.getKey());
        }
        return ResponseEntity.status(key.getResponseStatus())
                .header("Idempotent-Replay", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(key.getResponseBody());
    }

    private String requestHash(Object... parts) {
        try {
            return Integer.toHexString(mapper.writeValueAsString(parts).hashCode());
        } catch (JsonProcessingException ex) {
            return "0";
        }
    }

    private String serialize(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
