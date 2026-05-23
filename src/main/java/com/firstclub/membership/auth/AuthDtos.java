package com.firstclub.membership.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 255) String fullName,
            @Schema(description = "Optional cohort tag used by tier eligibility rules")
            @Size(max = 64) String cohort
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(
            Long userId,
            String email,
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            BigDecimal walletBalance
    ) {}
}
