package com.firstclub.membership.wallet;

import com.firstclub.membership.common.error.Errors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Owns mutations to the wallet aggregate. All public mutating methods require
 * an {@code idempotencyKey} so retries are safe — same key + same wallet
 * returns the original txn instead of double-charging.
 *
 * Concurrency: {@link Wallet} carries {@code @Version}, so two concurrent
 * mutations on the same wallet will conflict and the loser retries (or fails
 * with CONCURRENT_MODIFICATION via GlobalExceptionHandler).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;

    @Transactional(readOnly = true)
    public Wallet getByUserId(Long userId) {
        return wallets.findByUserId(userId)
                .orElseThrow(() -> Errors.notFound("Wallet", userId));
    }

    @Transactional
    public WalletTransaction debit(Long userId,
                                   BigDecimal amount,
                                   String referenceType,
                                   String referenceId,
                                   String idempotencyKey,
                                   String note) {
        return apply(userId, amount, WalletTransaction.Type.DEBIT,
                referenceType, referenceId, idempotencyKey, note);
    }

    @Transactional
    public WalletTransaction credit(Long userId,
                                    BigDecimal amount,
                                    String referenceType,
                                    String referenceId,
                                    String idempotencyKey,
                                    String note) {
        return apply(userId, amount, WalletTransaction.Type.CREDIT,
                referenceType, referenceId, idempotencyKey, note);
    }

    private WalletTransaction apply(Long userId,
                                    BigDecimal amount,
                                    WalletTransaction.Type type,
                                    String referenceType,
                                    String referenceId,
                                    String idempotencyKey,
                                    String note) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Wallet wallet = wallets.findByUserId(userId)
                .orElseThrow(() -> Errors.notFound("Wallet", userId));

        Optional<WalletTransaction> existing =
                transactions.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey);
        if (existing.isPresent()) {
            log.debug("Idempotent replay for wallet={} key={}", wallet.getId(), idempotencyKey);
            return existing.get();
        }

        if (type == WalletTransaction.Type.DEBIT) {
            wallet.debit(amount);
        } else {
            wallet.credit(amount);
        }
        // Persist the new balance via @Version-protected update.
        wallets.save(wallet);

        return transactions.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(type)
                .amount(amount)
                .balanceAfter(wallet.getBalance())
                .referenceType(referenceType)
                .referenceId(referenceId)
                .idempotencyKey(idempotencyKey)
                .note(note)
                .build());
    }
}
