package com.firstclub.membership.auth;

import com.firstclub.membership.common.error.Errors;
import com.firstclub.membership.config.AppProperties;
import com.firstclub.membership.user.User;
import com.firstclub.membership.user.UserRepository;
import com.firstclub.membership.wallet.Wallet;
import com.firstclub.membership.wallet.WalletRepository;
import com.firstclub.membership.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String SIGNUP_BONUS_REF_TYPE = "SIGNUP_BONUS";

    private final UserRepository users;
    private final WalletRepository wallets;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final AppProperties props;

    /**
     * Creates the user, wallet, and the seed-bonus ledger row in a single
     * transaction. Anything fails → nothing persists.
     */
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            throw Errors.conflict("A user with email %s already exists".formatted(req.email()));
        }

        User user = users.save(User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .cohort(req.cohort())
                .build());

        // Open the wallet at zero, then route the seed bonus through the same
        // ledger path every other credit uses — keeps audit history honest.
        wallets.save(Wallet.builder()
                .userId(user.getId())
                .balance(BigDecimal.ZERO)
                .build());

        walletService.credit(user.getId(),
                props.wallet().signupBonus(),
                SIGNUP_BONUS_REF_TYPE,
                user.getId().toString(),
                "signup-bonus-" + UUID.randomUUID(),
                "Welcome bonus on registration");

        Wallet wallet = walletService.getByUserId(user.getId());
        log.info("Registered user id={} with seed wallet balance={}", user.getId(), wallet.getBalance());
        return buildAuthResponse(user, wallet);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest req) {
        User user = users.findByEmail(req.email())
                .orElseThrow(() -> Errors.unauthorized("Invalid email or password"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw Errors.unauthorized("Invalid email or password");
        }
        Wallet wallet = wallets.findByUserId(user.getId())
                .orElseThrow(() -> Errors.notFound("Wallet", user.getId()));
        return buildAuthResponse(user, wallet);
    }

    private AuthDtos.AuthResponse buildAuthResponse(User user, Wallet wallet) {
        String token = jwt.issue(user.getId(), user.getEmail());
        return new AuthDtos.AuthResponse(
                user.getId(),
                user.getEmail(),
                token,
                "Bearer",
                props.security().jwt().accessTokenTtl().toSeconds(),
                wallet.getBalance()
        );
    }
}
