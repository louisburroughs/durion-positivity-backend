package com.positivity.invoice.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies short-lived, stateless HMAC tokens authorizing a single artifact download.
 *
 * <p>Token format: {@code base64url(payload).base64url(hmacSha256(payload))} where payload is
 * {@code invoiceId|artifactRefId|expiryEpochSeconds}. The token is bound to a specific invoice +
 * artifact and expires, so it can be handed to a browser for a direct download link.
 */
@Service
public class ArtifactTokenService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactTokenService.class);

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final Clock clock;
    private final byte[] signingKey;
    private final Duration ttl;

    public ArtifactTokenService(
            Clock clock,
            @Value("${invoice.artifacts.signing-secret:}") String signingSecret,
            @Value("${invoice.artifacts.token-ttl-seconds:300}") long ttlSeconds) {
        this.clock = clock;
        if (signingSecret == null || signingSecret.isBlank()) {
            // Fall back to a per-instance random key so the service still starts. Tokens are
            // short-lived, so the cost is only that outstanding tokens don't survive a restart and
            // aren't shared across instances. Set invoice.artifacts.signing-secret in production.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.signingKey = random;
            log.warn("invoice.artifacts.signing-secret is not configured; using a per-instance random key. "
                    + "Set it for stable, multi-instance artifact download tokens.");
        } else {
            this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Issue a token for the given invoice + artifact. */
    @NonNull
    public IssuedToken issue(@NonNull UUID invoiceId, @NonNull String artifactRefId) {
        Instant expiresAt = clock.instant().plus(ttl);
        String payload = payload(invoiceId, artifactRefId, expiresAt.getEpochSecond());
        String token = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verify a token against the expected invoice + artifact. Throws {@link InvalidTokenException}
     * if the signature is wrong, the binding mismatches, or it has expired.
     */
    public void verify(@NonNull String token, @NonNull UUID invoiceId, @NonNull String artifactRefId) {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new InvalidTokenException("Malformed download token");
        }
        String payload;
        try {
            payload = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException("Malformed download token");
        }
        String providedSig = token.substring(dot + 1);
        if (!constantTimeEquals(providedSig, sign(payload))) {
            throw new InvalidTokenException("Invalid download token signature");
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("Malformed download token payload");
        }
        if (!parts[0].equals(invoiceId.toString()) || !parts[1].equals(artifactRefId)) {
            throw new InvalidTokenException("Download token does not match the requested artifact");
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException ex) {
            throw new InvalidTokenException("Malformed download token expiry");
        }
        if (clock.instant().getEpochSecond() > exp) {
            throw new InvalidTokenException("Download token has expired");
        }
    }

    private String payload(UUID invoiceId, String artifactRefId, long expEpoch) {
        return invoiceId + "|" + artifactRefId + "|" + expEpoch;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGO));
            return B64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign artifact download token", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** A freshly issued token and its expiry. */
    public record IssuedToken(
            @NonNull String token, @NonNull Instant expiresAt) {}

    /** Raised when a presented download token is invalid, mismatched, or expired. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
