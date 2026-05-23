package com.firstclub.membership.billing;

import com.firstclub.membership.common.error.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single public entry point for collecting money. Subscription/tier-change
 * code never talks to a {@link PaymentMethod} directly — it asks BillingService
 * to charge "the user's payment method", and the service routes.
 *
 * Today this always resolves to {@code WALLET}; multi-PSP later is a one-line
 * change to {@link #resolveForCharge(Long)}.
 */
@Slf4j
@Service
public class BillingService {

    private final Map<String, PaymentMethod> methodsByCode;

    public BillingService(List<PaymentMethod> methods) {
        this.methodsByCode = methods.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentMethod::code, Function.identity()));
        log.info("Registered payment methods: {}", methodsByCode.keySet());
    }

    public BillingResult charge(Long userId, BigDecimal amount, PaymentMethod.ChargeContext ctx) {
        if (amount == null || amount.signum() <= 0) {
            throw Errors.badRequest("INVALID_AMOUNT", "amount must be positive");
        }
        PaymentMethod method = resolveForCharge(userId);
        String reference = method.charge(userId, amount, ctx);
        log.info("Charged user={} amount={} via {} (ref={})", userId, amount, method.code(), reference);
        return new BillingResult(method.code(), reference, amount);
    }

    public BillingResult refund(Long userId, BigDecimal amount, PaymentMethod.ChargeContext ctx) {
        if (amount == null || amount.signum() <= 0) {
            throw Errors.badRequest("INVALID_AMOUNT", "amount must be positive");
        }
        PaymentMethod method = resolveForCharge(userId);
        String reference = method.refund(userId, amount, ctx);
        log.info("Refunded user={} amount={} via {} (ref={})", userId, amount, method.code(), reference);
        return new BillingResult(method.code(), reference, amount);
    }

    private PaymentMethod resolveForCharge(Long userId) {
        // Single payment method for the demo; a real impl would look up the
        // user's default PaymentMethod row and dispatch accordingly.
        PaymentMethod method = methodsByCode.get(WalletPaymentMethod.CODE);
        if (method == null) {
            throw new IllegalStateException("No wallet payment method registered");
        }
        return method;
    }

    public record BillingResult(String methodCode, String reference, BigDecimal amount) {}
}
