package com.firstclub.membership.wallet;

import com.firstclub.membership.auth.AuthPrincipal;
import com.firstclub.membership.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Authenticated user's virtual wallet")
public class WalletController {

    private final WalletService wallets;
    private final WalletTransactionRepository transactions;

    @Operation(summary = "Current balance for the authenticated user")
    @GetMapping
    public WalletDto getMyWallet(@CurrentUser AuthPrincipal me) {
        Wallet w = wallets.getByUserId(me.userId());
        return new WalletDto(w.getId(), w.getBalance());
    }

    @Operation(summary = "Recent ledger entries (most-recent first)")
    @GetMapping("/transactions")
    public List<TransactionDto> getMyTransactions(@CurrentUser AuthPrincipal me,
                                                  @RequestParam(defaultValue = "20") int limit) {
        Wallet w = wallets.getByUserId(me.userId());
        int capped = Math.min(Math.max(limit, 1), 100);
        return transactions.findByWalletIdOrderByIdDesc(w.getId(), PageRequest.of(0, capped))
                .stream()
                .map(tx -> new TransactionDto(tx.getId(), tx.getType().name(), tx.getAmount(),
                        tx.getBalanceAfter(), tx.getReferenceType(), tx.getReferenceId(),
                        tx.getNote(), tx.getCreatedAt()))
                .toList();
    }

    public record WalletDto(Long id, BigDecimal balance) {}

    public record TransactionDto(Long id, String type, BigDecimal amount, BigDecimal balanceAfter,
                                 String referenceType, String referenceId, String note,
                                 Instant createdAt) {}
}
