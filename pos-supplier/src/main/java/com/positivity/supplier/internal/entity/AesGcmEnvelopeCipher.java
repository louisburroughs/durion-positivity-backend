package com.positivity.supplier.internal.entity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The AES-256-GCM envelope and fail-closed key policy that pos-supplier's ciphers share: the exchange-audit
 * payload cipher (ADR-0050 §7) and the vendor tax-registration number cipher (Security ruling on #2617,
 * ruling 3; #2621). Each cipher owns its own key, its own property names and its own exception type; this
 * class owns only the mechanics, so the two cannot drift apart. {@link AuditPayloadCipher}'s class javadoc
 * gives the full reasoning behind both the envelope and the key policy.
 *
 * <h2>Envelope</h2>
 *
 * <pre>
 * offset  bytes  meaning
 * 0       1      format version, currently 0x01
 * 1       1      key id length in bytes (1..255)
 * 2       n      key id, UTF-8
 * 2+n     12     nonce
 * 14+n    ..     ciphertext with the 128-bit GCM tag appended
 * </pre>
 *
 * <p>The AAD is the header followed by the caller's <em>bound context</em>. The exchange-audit cipher binds
 * nothing beyond the header; the vendor tax-id cipher binds tenant, vendor and registration, so a
 * ciphertext copied into another row fails authentication. The context is not stored: the reader must
 * supply the same bytes, which is the point.
 *
 * <h2>Key policy</h2>
 *
 * A real key is mandatory unless <strong>every</strong> active profile is {@code dev} or {@code test}; an
 * empty profile set requires one. See {@link AuditPayloadCipher} for why the polarity is an allowlist.
 *
 * <p>Nonces are 96 random bits per message from {@link SecureRandom}, never derived from anything.
 */
final class AesGcmEnvelopeCipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmEnvelopeCipher.class);

    static final byte ENVELOPE_VERSION = 0x01;
    static final int NONCE_LENGTH = 12;
    static final int GCM_TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte[] NO_CONTEXT = new byte[0];

    /** The only profiles in which an ephemeral key is acceptable. Everything else, none included, needs a key. */
    static final Set<String> KEY_OPTIONAL_PROFILES = Set.of("dev", "test");

    /**
     * How one cipher names itself in configuration, messages and logs.
     *
     * @param propertyPrefix e.g. {@code pos.supplier.audit.encryption}; {@code .key}, {@code .key-id} and
     *     {@code .previous-keys} hang off it
     * @param environmentVariable the variable {@code application.yml} binds the key from; the failure message
     *     names it, so it must be the one actually bound
     * @param subject what is sealed, for decryption messages, e.g. {@code Exchange-audit payload}
     * @param keyName what the key is called in key messages, e.g. {@code Exchange-audit}
     * @param reference the decision that requires the key, e.g. {@code ADR-0050 §7}
     */
    record Naming(
            @NonNull String propertyPrefix,
            @NonNull String environmentVariable,
            @NonNull String subject,
            @NonNull String keyName,
            @NonNull String reference) {}

    /** Why an envelope could not be opened. */
    enum Failure {
        /** Wrong version byte, truncated, or impossible lengths. */
        MALFORMED_ENVELOPE,
        /** The envelope names a key id this deployment has no key for. */
        UNKNOWN_KEY_ID,
        /** The ciphertext, its header or its bound context was modified, or the key is wrong. */
        AUTHENTICATION_FAILED
    }

    /** Builds the owning cipher's own exception. Messages carry the key id and failure kind only. */
    @FunctionalInterface
    interface UnreadableFactory {
        RuntimeException create(
                @NonNull Failure failure, @Nullable String keyId, @NonNull String message, @Nullable Throwable cause);
    }

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SecretKey> keysById;
    private final String activeKeyId;
    private final Naming naming;
    private final UnreadableFactory unreadable;

    AesGcmEnvelopeCipher(
            @NonNull Environment environment,
            @Nullable String activeKeyBase64,
            @Nullable String configuredKeyId,
            @Nullable String previousKeys,
            @NonNull Naming naming,
            @NonNull UnreadableFactory unreadable) {
        Objects.requireNonNull(environment, "environment must not be null");
        this.naming = Objects.requireNonNull(naming, "naming must not be null");
        this.unreadable = Objects.requireNonNull(unreadable, "unreadable must not be null");
        Map<String, SecretKey> keys = new LinkedHashMap<>();

        if (activeKeyBase64 == null || activeKeyBase64.isBlank()) {
            if (isKeyRequired(environment)) {
                // Fail closed. Starting without a key would either lose data on the next restart or write
                // ciphertext nobody can read back.
                throw new IllegalStateException(naming.propertyPrefix() + ".key is required unless one of"
                        + " the profiles " + KEY_OPTIONAL_PROFILES + " is active (active: "
                        + Arrays.toString(environment.getActiveProfiles())
                        + "). Provision " + naming.environmentVariable()
                        + " (32 bytes, base64) before starting pos-supplier"
                        + " (" + naming.reference() + "). An empty profile set deliberately requires a key: a"
                        + " fail-closed control must not depend on a profile having been set correctly.");
            }
            this.activeKeyId = configuredKeyId == null || configuredKeyId.isBlank() ? "k1" : configuredKeyId;
            keys.put(this.activeKeyId, generateEphemeralKey());
            log.warn(
                    "{}.key is not set; generated an EPHEMERAL {} key for this JVM only. Everything sealed now"
                            + " becomes PERMANENTLY unreadable on restart, and will then report as an"
                            + " authentication failure. Reached only because profile {} is active; any other"
                            + " profile, or none, fails startup instead.",
                    naming.propertyPrefix(),
                    naming.keyName(),
                    Arrays.toString(environment.getActiveProfiles()));
        } else {
            this.activeKeyId = requireUsableKeyId(configuredKeyId);
            keys.put(this.activeKeyId, parseKey(this.activeKeyId, activeKeyBase64));
        }

        // Decrypt-only keys keep pre-rotation rows readable.
        for (Map.Entry<String, String> previous :
                parsePreviousKeys(previousKeys).entrySet()) {
            keys.putIfAbsent(previous.getKey(), parseKey(previous.getKey(), previous.getValue()));
        }
        this.keysById = Map.copyOf(keys);
    }

    /** Seals {@code plaintext} under the active key with the header as AAD. */
    byte @NonNull [] encrypt(byte @NonNull [] plaintext) {
        return encrypt(plaintext, NO_CONTEXT);
    }

    /**
     * Seals {@code plaintext} under the active key; the AAD is the header followed by {@code boundContext}.
     *
     * @param plaintext the bytes to seal
     * @param boundContext bytes the reader must present again to open the envelope; not stored
     * @return the envelope described in the class javadoc
     */
    byte @NonNull [] encrypt(byte @NonNull [] plaintext, byte @NonNull [] boundContext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        Objects.requireNonNull(boundContext, "boundContext must not be null");
        byte[] keyIdBytes = activeKeyId.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[2 + keyIdBytes.length];
        header[0] = ENVELOPE_VERSION;
        header[1] = (byte) keyIdBytes.length;
        System.arraycopy(keyIdBytes, 0, header, 2, keyIdBytes.length);

        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);

        byte[] ciphertext;
        try {
            // ── semgrep java.lang.security.audit.crypto.gcm-detection ──────────────────
            // AUDIT-category rule: it asks a human to confirm the GCM nonce is never reused. Evidence:
            //   1. `secureRandom.nextBytes(nonce)` above draws NONCE_LENGTH (12) fresh bytes from
            //      SecureRandom on every single call to encrypt().
            //   2. The nonce is never derived from a counter, a timestamp, the key id, or anything about
            //      the row -- there is no code path that reuses or re-seeds it.
            //   3. AuditPayloadCipherTest.NonceDiscipline and VendorTaxIdCipherTest encrypt one identical
            //      plaintext many times under one key and assert distinct nonces, so a regression to a
            //      fixed or derived nonce fails the build.
            // Bare form: it suppresses only the single following line.
            // nosemgrep
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // Same finding, second match location; same evidence as directly above.
            // nosemgrep
            cipher.init(Cipher.ENCRYPT_MODE, keysById.get(activeKeyId), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            // Bind the header (and the caller's context) so none of it can be rewritten without failing the tag.
            cipher.updateAAD(header);
            if (boundContext.length > 0) {
                cipher.updateAAD(boundContext);
            }
            ciphertext = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException ex) {
            // Never include the plaintext in the message: it is what we are protecting.
            throw new IllegalStateException(naming.subject() + " encryption failed", ex);
        }

        byte[] envelope = new byte[header.length + NONCE_LENGTH + ciphertext.length];
        System.arraycopy(header, 0, envelope, 0, header.length);
        System.arraycopy(nonce, 0, envelope, header.length, NONCE_LENGTH);
        System.arraycopy(ciphertext, 0, envelope, header.length + NONCE_LENGTH, ciphertext.length);
        return envelope;
    }

    /** Opens an envelope sealed with no bound context. */
    byte @NonNull [] decrypt(byte @NonNull [] envelope) {
        return decrypt(envelope, NO_CONTEXT);
    }

    /**
     * Opens an envelope produced by {@link #encrypt(byte[], byte[])} with the same {@code boundContext}.
     *
     * @throws RuntimeException the owning cipher's own unreadable exception, built by its factory, when the
     *     envelope is malformed, names an unconfigured key, or fails authentication
     */
    byte @NonNull [] decrypt(byte @NonNull [] envelope, byte @NonNull [] boundContext) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(boundContext, "boundContext must not be null");
        if (envelope.length < 2) {
            throw malformed("envelope is shorter than its header");
        }
        if (envelope[0] != ENVELOPE_VERSION) {
            throw malformed("unsupported envelope version 0x" + Integer.toHexString(envelope[0] & 0xFF));
        }
        int keyIdLength = envelope[1] & 0xFF;
        if (keyIdLength == 0) {
            throw malformed("envelope declares a zero-length key id");
        }
        int headerLength = 2 + keyIdLength;
        // Require at least one byte of ciphertext beyond the GCM tag's 16 bytes.
        if (envelope.length < headerLength + NONCE_LENGTH + (GCM_TAG_BITS / 8)) {
            throw malformed("envelope is truncated");
        }

        String keyId = new String(envelope, 2, keyIdLength, StandardCharsets.UTF_8);
        SecretKey key = keysById.get(keyId);
        if (key == null) {
            throw unreadable.create(
                    Failure.UNKNOWN_KEY_ID,
                    keyId,
                    naming.subject() + " was sealed with key id '" + keyId
                            + "', which is not configured. Add it to " + naming.propertyPrefix()
                            + ".previous-keys to read pre-rotation rows.",
                    null);
        }

        byte[] header = new byte[headerLength];
        System.arraycopy(envelope, 0, header, 0, headerLength);
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(envelope, headerLength, nonce, 0, NONCE_LENGTH);
        int cipherOffset = headerLength + NONCE_LENGTH;

        try {
            // ── semgrep gcm-detection, decrypt path ────────────────────────────────────
            // Nothing here generates a nonce: it is read back out of the stored envelope above, so the
            // rule's concern (nonce reuse at encryption time) cannot arise on this path.
            // nosemgrep
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // Same finding, second match location; same reasoning as directly above.
            // nosemgrep
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            if (boundContext.length > 0) {
                cipher.updateAAD(boundContext);
            }
            return cipher.doFinal(envelope, cipherOffset, envelope.length - cipherOffset);
        } catch (AEADBadTagException ex) {
            // Authentication failure: the ciphertext, its header or its bound context was modified, or the
            // key id maps to the wrong key. Potential tampering evidence, so it stays distinguishable.
            throw unreadable.create(
                    Failure.AUTHENTICATION_FAILED,
                    keyId,
                    naming.subject() + " failed authentication under key id '" + keyId
                            + "'; the stored bytes or their header do not match the tag",
                    ex);
        } catch (GeneralSecurityException ex) {
            throw unreadable.create(
                    Failure.MALFORMED_ENVELOPE,
                    keyId,
                    naming.subject() + " could not be decrypted under key id '" + keyId + "'",
                    ex);
        }
    }

    /** The key id new envelopes are sealed with. */
    @NonNull
    String activeKeyId() {
        return activeKeyId;
    }

    /** Key ids this deployment can decrypt, active plus decrypt-only. */
    @NonNull
    Set<String> readableKeyIds() {
        return keysById.keySet();
    }

    /**
     * Required unless EVERY active profile is optional, and required when no profile is active at all, which
     * is checked separately because {@code allMatch} on an empty array is vacuously true.
     */
    static boolean isKeyRequired(@NonNull Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 || !Arrays.stream(active).allMatch(KEY_OPTIONAL_PROFILES::contains);
    }

    @NonNull
    private String requireUsableKeyId(@Nullable String configuredKeyId) {
        if (configuredKeyId == null || configuredKeyId.isBlank()) {
            throw new IllegalStateException(naming.propertyPrefix() + ".key-id must not be blank");
        }
        int length = configuredKeyId.getBytes(StandardCharsets.UTF_8).length;
        if (length > 255) {
            // The envelope encodes key id length in one unsigned byte.
            throw new IllegalStateException(naming.propertyPrefix() + ".key-id must be at most 255 bytes");
        }
        return configuredKeyId;
    }

    @NonNull
    private SecretKey parseKey(@NonNull String keyId, @NonNull String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            // Never echo the value: it is key material even when malformed.
            throw new IllegalStateException(
                    naming.keyName() + " encryption key '" + keyId + "' is not valid base64", ex);
        }
        if (raw.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(naming.keyName() + " encryption key '" + keyId + "' must be "
                    + AES_256_KEY_BYTES + " bytes for AES-256 but decoded to " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    @NonNull
    private Map<String, String> parsePreviousKeys(@Nullable String configured) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (configured == null || configured.isBlank()) {
            return parsed;
        }
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalStateException(
                        naming.propertyPrefix() + ".previous-keys entries must be 'keyId:base64'");
            }
            String candidateValue = trimmed.substring(separator + 1);
            // A base-64 value cannot contain ':', so a second colon means the KEY ID contains one and the
            // split landed inside it. Reported as an id problem, not as a malformed key.
            if (candidateValue.indexOf(':') >= 0) {
                throw new IllegalStateException(naming.propertyPrefix() + ".previous-keys key ids must not"
                        + " contain ':' — it separates the key id from its base64 value, so an id containing"
                        + " one cannot be parsed unambiguously");
            }
            parsed.put(trimmed.substring(0, separator), candidateValue);
        }
        return parsed;
    }

    @NonNull
    private SecretKey generateEphemeralKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(AES_256_KEY_BYTES * 8);
            return generator.generateKey();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate an ephemeral " + naming.keyName() + " key", ex);
        }
    }

    @NonNull
    private RuntimeException malformed(@NonNull String detail) {
        return unreadable.create(
                Failure.MALFORMED_ENVELOPE, null, naming.subject() + " envelope is malformed: " + detail, null);
    }
}
