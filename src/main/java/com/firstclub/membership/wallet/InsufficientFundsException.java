package com.firstclub.membership.wallet;

import com.firstclub.membership.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class InsufficientFundsException extends ApiException {
    public InsufficientFundsException(Long userId, BigDecimal balance, BigDecimal required) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Wallet for user %d has %s; required %s".formatted(userId, balance, required));
    }
}
