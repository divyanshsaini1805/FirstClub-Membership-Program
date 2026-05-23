package com.firstclub.membership.billing;

import com.firstclub.membership.wallet.WalletService;
import com.firstclub.membership.wallet.WalletTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class WalletPaymentMethod implements PaymentMethod {

    public static final String CODE = "WALLET";

    private final WalletService wallet;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String charge(Long userId, BigDecimal amount, ChargeContext ctx) {
        WalletTransaction tx = wallet.debit(userId, amount,
                ctx.referenceType(), ctx.referenceId(), ctx.idempotencyKey(), ctx.note());
        return Long.toString(tx.getId());
    }

    @Override
    public String refund(Long userId, BigDecimal amount, ChargeContext ctx) {
        WalletTransaction tx = wallet.credit(userId, amount,
                ctx.referenceType(), ctx.referenceId(), ctx.idempotencyKey(), ctx.note());
        return Long.toString(tx.getId());
    }
}
