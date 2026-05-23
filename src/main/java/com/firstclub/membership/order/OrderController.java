package com.firstclub.membership.order;

import com.firstclub.membership.auth.AuthPrincipal;
import com.firstclub.membership.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Demo endpoint — every order drives tier eligibility re-evaluation")
public class OrderController {

    private final OrderService orders;

    @Operation(summary = "Place a (stub) order; triggers tier eligibility re-eval")
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @CurrentUser AuthPrincipal me,
            @Valid @RequestBody PlaceOrderRequest req) {
        Order order = orders.placeOrder(me.userId(), req.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new OrderResponse(order.getId(), order.getAmount(), order.getPlacedAt()));
    }

    public record PlaceOrderRequest(@NotNull @Positive BigDecimal amount) {}
    public record OrderResponse(Long id, BigDecimal amount, Instant placedAt) {}
}
