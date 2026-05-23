package com.firstclub.membership;

import com.firstclub.membership.auth.AuthDtos;
import com.firstclub.membership.auth.AuthService;
import com.firstclub.membership.subscription.Subscription;
import com.firstclub.membership.subscription.SubscriptionLifecycleService;
import com.firstclub.membership.subscription.SubscriptionStatus;
import com.firstclub.membership.wallet.Wallet;
import com.firstclub.membership.wallet.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionLifecycleIT extends AbstractIntegrationTest {

    @Autowired AuthService auth;
    @Autowired SubscriptionLifecycleService lifecycle;
    @Autowired WalletRepository wallets;

    @Test
    void subscribe_chargesWallet_andRecordsActiveSubscription() {
        AuthDtos.AuthResponse user = auth.register(new AuthDtos.RegisterRequest(
                "lifecycle-" + System.nanoTime() + "@x.com", "password123", "T", null));

        Subscription sub = lifecycle.subscribe(user.userId(), 1L, 1L, "first-key");

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        Wallet w = wallets.findByUserId(user.userId()).orElseThrow();
        // signup bonus minus subscription cost (Monthly base 199, Silver 0)
        assertThat(w.getBalance()).isEqualByComparingTo(new BigDecimal("49801.00"));
    }

    @Test
    void upgrade_prorates_and_downgrade_schedules() {
        AuthDtos.AuthResponse user = auth.register(new AuthDtos.RegisterRequest(
                "tierchange-" + System.nanoTime() + "@x.com", "password123", "T", null));
        Subscription sub = lifecycle.subscribe(user.userId(), 3L /*YEARLY*/, 1L /*SILVER*/, null);

        // Upgrade to Gold — close to full Gold price because we just subscribed.
        var upgrade = lifecycle.changeTier(user.userId(), sub.getId(), 2L, null);
        assertThat(upgrade.kind()).isEqualTo(SubscriptionLifecycleService.ChangeKind.UPGRADED_IMMEDIATELY);
        assertThat(upgrade.amountCharged()).isGreaterThan(new BigDecimal("498.00"));

        // Downgrade to Silver — no charge, scheduled for period end.
        var downgrade = lifecycle.changeTier(user.userId(), sub.getId(), 1L, null);
        assertThat(downgrade.kind()).isEqualTo(SubscriptionLifecycleService.ChangeKind.DOWNGRADE_SCHEDULED);
        assertThat(downgrade.amountCharged()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(downgrade.takesEffectAt()).isEqualTo(sub.getEndsAt());
    }

    @Test
    void cancel_isIdempotent() {
        AuthDtos.AuthResponse user = auth.register(new AuthDtos.RegisterRequest(
                "cancel-" + System.nanoTime() + "@x.com", "password123", "T", null));
        Subscription sub = lifecycle.subscribe(user.userId(), 1L, 1L, null);

        Subscription first = lifecycle.cancel(user.userId(), sub.getId());
        Subscription second = lifecycle.cancel(user.userId(), sub.getId());

        assertThat(first.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(first.getCancelledAt()).isEqualTo(second.getCancelledAt());
    }
}
