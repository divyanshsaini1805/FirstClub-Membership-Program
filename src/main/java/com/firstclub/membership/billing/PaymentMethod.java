package com.firstclub.membership.billing;

import java.math.BigDecimal;

/**
 * Strategy for collecting money from a user. Today the only implementation is
 * {@code WalletPaymentMethod}, but a real PSP (Stripe, Razorpay, …) would slot
 * in as another implementation without touching callers.
 *
 * Each method must be **idempotent** when given the same {@link ChargeContext#idempotencyKey()} —
 * the wallet implementation enforces this via a unique index on the ledger
 * table; a real PSP would forward the key as its own idempotency header.
 */
public interface PaymentMethod {

    /** Identifier exposed in the wire format and persisted alongside subscriptions. */
    String code();

    /** Idempotent debit. Returns the persisted txn id (or PSP reference). */
    String charge(Long userId, BigDecimal amount, ChargeContext ctx);

    /** Idempotent credit (refunds, reversals). */
    String refund(Long userId, BigDecimal amount, ChargeContext ctx);

    /**
     * Context required for traceability + idempotency. {@code referenceType}
     * and {@code referenceId} let us join back to the originating domain row
     * (e.g. SUBSCRIPTION, TIER_CHANGE).
     */
    record ChargeContext(
            String referenceType,
            String referenceId,
            String idempotencyKey,
            String note
    ) {}
}
