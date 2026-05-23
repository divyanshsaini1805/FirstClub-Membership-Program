package com.firstclub.membership.wallet;

import com.firstclub.membership.common.persistence.AppendOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "wallet_transactions")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTransaction extends AppendOnlyEntity {

    public enum Type { CREDIT, DEBIT }

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "reference_type", length = 64)
    private String referenceType;

    @Column(name = "reference_id", length = 128)
    private String referenceId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(length = 512)
    private String note;
}
