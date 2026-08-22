package com.transactionwatch.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.function.Function;

/**
 * AUTH APPROACH — justification:
 *   TransactionWatch does NOT run its own login endpoint or user table. It
 *   trusts JWTs issued by SecureLedger's /auth/login, verified here against
 *   the same HMAC secret, and requires the token's "role" claim to be ADMIN.
 *
 *   Why: building a second, independent admin-credential store for a
 *   service that only exists to support the first one is duplicated attack
 *   surface for no real benefit in this system — a bank doesn't want two
 *   separate places that can mint "I am an admin" tokens. Reusing the
 *   issuer keeps the admin identity single-sourced.
 *
 *   The honest tradeoff: this couples the two services via a shared secret
 *   (both must be configured with the identical JWT_SECRET), which is a
 *   real operational coupling — rotating the secret means coordinating a
 *   deploy of both services, and any service holding the secret can also
 *   forge tokens for the other. The production-grade version of this
 *   pattern is an actual authorization server (issuing via RS256/asymmetric
 *   keys, so verifiers only need a public key, not a shared secret) or a
 *   dedicated OAuth2/OIDC provider — noted here as the real next step
 *   rather than implemented, per project scope.
 */
@Component
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public boolean isTokenValid(String token) {
        try {
            return !isExpired(token);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return resolver.apply(claims);
    }
}
