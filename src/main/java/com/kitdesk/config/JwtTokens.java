package com.kitdesk.config;

import com.kitdesk.domain.Role;
import com.kitdesk.service.Caller;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and checks login tokens: JWTs signed with HS256 using a secret from the JWT_SECRET
 * environment variable. A token holds the user's id ({@code sub}), their role and an expiry
 * (30 minutes by default). Nothing else: subscription and overdue status are read from the
 * database on each request, so a token can't carry stale eligibility.
 */
@Component
public class JwtTokens {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration lifetime;
    private final Clock clock;

    public JwtTokens(@Value("${kitdesk.jwt.secret:}") String secret,
                     @Value("${kitdesk.jwt.lifetime:PT30M}") Duration lifetime,
                     Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 needs a key of at least 256 bits. Refuse to start rather than sign with a weak key.
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least 32 characters (see .env.example).");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public String issue(Long userId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long lifetimeSeconds() {
        return lifetime.toSeconds();
    }

    /**
     * The caller named by a valid token, or empty if the token is forged, tampered with, expired,
     * unsigned, or signed with anything other than our HMAC key.
     */
    public Optional<Caller> verify(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(key)           // only accepts tokens signed with our key (no "alg: none")
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            Role role = Role.valueOf(claims.get(ROLE_CLAIM, String.class));
            return Optional.of(new Caller(userId, role));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
