package com.firstclub.membership.auth;

import com.firstclub.membership.common.error.Errors;
import com.firstclub.membership.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";

    private final AppProperties props;
    private final SecretKey signingKey;
    private final Clock clock;

    @Autowired
    public JwtService(AppProperties props) {
        this(props, Clock.systemUTC());
    }

    JwtService(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.signingKey = resolveKey(props.security().jwt().secret());
    }

    /**
     * Treats the configured secret as raw UTF-8 bytes for HS256. Demo simplicity;
     * production would use a base64- or hex-encoded key derived from a KMS.
     */
    private static SecretKey resolveKey(String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes (got " + raw.length + ")");
        }
        return Keys.hmacShaKeyFor(raw);
    }

    public String issue(long userId, String email) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.security().jwt().accessTokenTtl());
        return Jwts.builder()
                .issuer(props.security().jwt().issuer())
                .subject(Long.toString(userId))
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(props.security().jwt().issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = Long.parseLong(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            return new ParsedToken(userId, email);
        } catch (JwtException | IllegalArgumentException ex) {
            throw Errors.unauthorized("Invalid or expired token");
        }
    }

    public record ParsedToken(long userId, String email) {}
}
