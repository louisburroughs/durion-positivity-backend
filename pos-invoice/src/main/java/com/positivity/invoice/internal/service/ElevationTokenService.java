package com.positivity.invoice.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Mints and verifies short-lived, HMAC-signed manager-approval elevation tokens.
 *
 * <p>A token authorises finalization of a single invoice on behalf of a manager who
 * holds {@code invoice:finalize:override}. The token is opaque to the client and is
 * verified server-side on the finalize call — it is the only accepted form of manager
 * approval code (a free-text string is no longer sufficient).
 *
 * <p>Format: {@code base64url(payload) + "." + base64url(hmacSha256(payload))} where
 * {@code payload = invoiceId + "|" + managerPersonId + "|" + expiryEpochSeconds}.
 */
@Service
public class ElevationTokenService {

    private static final Logger log = LoggerFactory.getLogger(ElevationTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String DELIMITER = "|";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final Clock clock;
    private final byte[] secret;
    private final long ttlSeconds;

    public ElevationTokenService(
            Clock clock,
            @Value("${invoice.elevation.token-secret:}") String tokenSecret,
            @Value("${invoice.elevation.token-ttl-seconds:300}") long ttlSeconds) {
        this.clock = clock;
        this.secret = tokenSecret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Mint a token authorising finalization of {@code invoiceId} approved by
     * {@code managerPersonId}.
     *
     * @return the signed token and its expiry instant
     */
    @NonNull
    public MintedToken mint(@NonNull UUID invoiceId, @NonNull UUID managerPersonId) {
        requireSecret();
        Instant expiresAt = Instant.now(clock).plusSeconds(ttlSeconds);
        String payload = invoiceId + DELIMITER + managerPersonId + DELIMITER + expiresAt.getEpochSecond();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String token = B64.encodeToString(payloadBytes) + "." + B64.encodeToString(sign(payloadBytes));
        return new MintedToken(token, expiresAt);
    }

    /**
     * Verify a token against the expected invoice scope.
     *
     * @return the approving manager's person id when the token is valid (signature
     *         matches, not expired, invoice scope matches); empty otherwise
     */
    @NonNull
    public Optional<UUID> verify(@NonNull String token, @NonNull UUID expectedInvoiceId) {
        requireSecret();
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        try {
            byte[] payloadBytes = B64_DEC.decode(token.substring(0, dot));
            byte[] providedSig = B64_DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(providedSig, sign(payloadBytes))) {
                return Optional.empty();
            }
            String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\" + DELIMITER);
            if (parts.length != 3) {
                return Optional.empty();
            }
            UUID invoiceId = UUID.fromString(parts[0]);
            UUID managerPersonId = UUID.fromString(parts[1]);
            long expEpoch = Long.parseLong(parts[2]);
            if (!invoiceId.equals(expectedInvoiceId)) {
                return Optional.empty();
            }
            if (Instant.now(clock).getEpochSecond() > expEpoch) {
                return Optional.empty();
            }
            return Optional.of(managerPersonId);
        } catch (IllegalArgumentException ex) {
            // Malformed base64, UUID, or numeric payload — treat as invalid token.
            log.debug("Rejected malformed elevation token: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Minimum secret length for HmacSHA256 — a full 256-bit key. */
    private static final int MIN_SECRET_BYTES = 32;

    private void requireSecret() {
        if (secret.length == 0) {
            throw new IllegalStateException(
                    "Elevation token secret is not configured (invoice.elevation.token-secret)");
        }
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("Elevation token secret is too short; require at least " + MIN_SECRET_BYTES
                    + " bytes (invoice.elevation.token-secret)");
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign elevation token", ex);
        }
    }

    /** A freshly minted token and its expiry. */
    public record MintedToken(
            @NonNull String token, @NonNull Instant expiresAt) {}
}
