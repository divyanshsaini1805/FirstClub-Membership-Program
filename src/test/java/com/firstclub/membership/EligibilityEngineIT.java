package com.firstclub.membership;

import com.firstclub.membership.auth.AuthDtos;
import com.firstclub.membership.auth.AuthService;
import com.firstclub.membership.eligibility.TierEligibilityService;
import com.firstclub.membership.eligibility.TierPromotion;
import com.firstclub.membership.order.OrderService;
import com.firstclub.membership.subscription.SubscriptionLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEngineIT extends AbstractIntegrationTest {

    @Autowired AuthService auth;
    @Autowired SubscriptionLifecycleService lifecycle;
    @Autowired OrderService orders;
    @Autowired TierEligibilityService eligibility;

    @Test
    void orderCountRule_promotesToGold() {
        var user = auth.register(new AuthDtos.RegisterRequest(
                "ec-" + System.nanoTime() + "@x.com", "password123", "Alice", null));
        lifecycle.subscribe(user.userId(), 1L /*MONTHLY*/, 1L /*SILVER*/, null);

        for (int i = 0; i < 5; i++) {
            orders.placeOrder(user.userId(), new BigDecimal("1000"));
        }
        Optional<TierPromotion> promo = eligibility.reevaluate(user.userId());
        assertThat(promo).isPresent();
        assertThat(promo.get().getPromotedTier().getCode()).isEqualTo("GOLD");
    }

    @Test
    void monthlyOrderValueRule_supersedesOrderCount() {
        var user = auth.register(new AuthDtos.RegisterRequest(
                "ec-" + System.nanoTime() + "@x.com", "password123", "Alice", null));
        lifecycle.subscribe(user.userId(), 1L, 1L, null);

        // Trigger Gold first.
        for (int i = 0; i < 5; i++) orders.placeOrder(user.userId(), new BigDecimal("1000"));
        // Then push monthly total over 20k → Platinum (higher rank).
        orders.placeOrder(user.userId(), new BigDecimal("25000"));

        var promo = eligibility.reevaluate(user.userId()).orElseThrow();
        assertThat(promo.getPromotedTier().getCode()).isEqualTo("PLATINUM");
    }

    @Test
    void cohortRule_promotesImmediately() {
        var user = auth.register(new AuthDtos.RegisterRequest(
                "ec-" + System.nanoTime() + "@x.com", "password123", "Alice", "VIP_BETA"));
        lifecycle.subscribe(user.userId(), 1L, 1L, null);

        var promo = eligibility.reevaluate(user.userId()).orElseThrow();
        assertThat(promo.getPromotedTier().getCode()).isEqualTo("PLATINUM");
    }
}
