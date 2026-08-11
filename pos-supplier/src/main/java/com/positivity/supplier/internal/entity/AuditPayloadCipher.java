package com.positivity.supplier.internal.entity;

import com.positivity.supplier.internal.exception.PayloadUnreadableException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of exchange-audit payloads at rest (ADR-0050 §7, binding decision 1).
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
 * <p>The header (version and key id) is passed as <strong>AAD</strong>, so it is covered by the GCM
 * tag. Without that, an attacker could rewrite the key id in place and the failure would look like
 * ordinary misconfiguration rather than tampering.
 *
 * <p>The key id is what makes rotation possible: each row records which key sealed it, so a new
 * active key can be introduced while old rows stay readable through decrypt-only keys. A 400-day
 * retention window (ADR-0050 §7) outlives any sane key lifetime, so a rotation path is not optional
 * — without one, rotating a key would silently make every existing payload unreadable.
 *
 * <h2>Key policy</h2>
 *
 * A real key is <strong>mandatory</strong> in {@code prod}, {@code indus} and {@code alpha}: startup
 * fails rather than quietly writing recoverable payloads or, worse, writing under an ephemeral key
 * that vanishes on restart and takes the audit trail with it. In {@code dev} and {@code test} an
 * ephemeral key is generated with a loud warning, so nobody needs secrets to run tests.
 *
 * <p>Nonces are 96 random bits per message from {@link SecureRandom}. A nonce must never repeat under
 * one key: GCM nonce reuse leaks the XOR of plaintexts and enables forgery, so nonces are never
 * derived from a counter, a timestamp, or anything about the row.
 */
@Component
public class AuditPayloadCipher {

    private static final Logger log = LoggerFactory.getLogger(AuditPayloadCipher.class);

    static final byte ENVELOPE_VERSION = 0x01;
    static final int NONCE_LENGTH = 12;
    static final int GCM_TAG_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Profiles where a real, operator-provisioned key is required. */
    private static final Set<String> KEY_REQUIRED_PROFILES = Set.of("prod", "indus", "alpha");

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SecretKey> keysById;
    private final String activeKeyId;

    public AuditPayloadCipher(
            @NonNull Environment environment,
            @Value("${pos.supplier.audit.encryption.key:}") String activeKeyBase64,
            @Value("${pos.supplier.audit.encryption.key-id:k1}") String configuredKeyId,
            @Value("${pos.supplier.audit.encryption.previous-keys:}") String previousKeys) {
        Objects.requireNonNull(environment, "environment must not be null");
        boolean keyRequired = isKeyRequired(environment);
        Map<String, SecretKey> keys = new LinkedHashMap<>();

        if (activeKeyBase64 == null || activeKeyBase64.isBlank()) {
            if (keyRequired) {
                // Fail closed. Starting without a key in a real environment would either lose the
                // audit trail on the next restart or write payloads nobody can read back.
                throw new IllegalStateException("pos.supplier.audit.encryption.key is required in profiles "
                        + KEY_REQUIRED_PROFILES + " but is not set. Provision SUPPLIER_AUDIT_ENC_KEY"
                        + " (32 bytes, base64) before starting pos-supplier (ADR-0050 §7).");
            }
            this.activeKeyId = configuredKeyId;
            keys.put(configuredKeyId, generateEphemeralKey());
            log.warn("pos.supplier.audit.encryption.key is not set; generated an EPHEMERAL exchange-audit"
                    + " key for this JVM only. Payloads written now become unreadable on restart."
                    + " Acceptable in dev/test only.");
        } else {
            this.activeKeyId = requireUsableKeyId(configuredKeyId);
            keys.put(this.activeKeyId, parseKey(this.activeKeyId, activeKeyBase64));
        }

        // Decrypt-only keys keep pre-rotation rows readable for the whole retention window.
        for (Map.Entry<String, String> previous :
                parsePreviousKeys(previousKeys).entrySet()) {
            keys.putIfAbsent(previous.getKey(), parseKey(previous.getKey(), previous.getValue()));
        }
        this.keysById = Map.copyOf(keys);
    }

    /**
     * Encrypts a payload under the active key.
     *
     * @param plaintext the payload; may be empty but not {@code null}
     * @return the envelope described in the class javadoc
     */
    public byte @NonNull [] encrypt(@NonNull String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
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
            // AUDIT-category rule: it does not assert a defect, it asks a human to confirm the GCM
            // nonce is never reused. Evidence that it is not:
            //   1. `secureRandom.nextBytes(nonce)` fourteen lines above draws NONCE_LENGTH (12)
            //      fresh bytes from SecureRandom on every single call to encrypt().
            //   2. The nonce is never derived from a counter, a timestamp, the key id, or anything
            //      about the row -- there is no code path that reuses or re-seeds it.
            //   3. AuditPayloadCipherTest.NonceDiscipline
            //      .neverReusesANonceForTheSameKeyAndPayload encrypts one identical plaintext 500
            //      times under one key and asserts 500 distinct nonces AND 500 distinct
            //      ciphertexts, so a regression to a fixed or derived nonce fails the build.
            // Scope: the bare form suppresses only the single following line. Bare rather than
            // canonical-id because the local lint hook derives rule ids from its rules-clone path,
            // so an id-qualified suppression matches in CI but silently misses locally.
            // nosemgrep
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // Same finding, second match location; same evidence as directly above.
            // nosemgrep
            cipher.init(Cipher.ENCRYPT_MODE, keysById.get(activeKeyId), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            // Bind the header so version and key id cannot be rewritten without failing the tag.
            cipher.updateAAD(header);
            ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // Never include the plaintext in the message: it is the payload we are protecting.
            throw new IllegalStateException("Exchange-audit payload encryption failed", ex);
        }

        byte[] envelope = new byte[header.length + NONCE_LENGTH + ciphertext.length];
        System.arraycopy(header, 0, envelope, 0, header.length);
        System.arraycopy(nonce, 0, envelope, header.length, NONCE_LENGTH);
        System.arraycopy(ciphertext, 0, envelope, header.length + NONCE_LENGTH, ciphertext.length);
        return envelope;
    }

    /**
     * Decrypts an envelope produced by {@link #encrypt(String)}.
     *
     * @param envelope the stored bytes
     * @return the payload
     * @throws PayloadUnreadableException when the envelope is malformed, names an unconfigured key,
     *     or fails authentication
     */
    @NonNull
    public String decrypt(byte @NonNull [] envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
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
            throw new PayloadUnreadableException(
                    PayloadUnreadableException.UNKNOWN_KEY_ID,
                    "Exchange-audit payload was sealed with key id '" + keyId
                            + "', which is not configured. Add it to"
                            + " pos.supplier.audit.encryption.previous-keys to read pre-rotation rows.");
        }

        byte[] header = new byte[headerLength];
        System.arraycopy(envelope, 0, header, 0, headerLength);
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(envelope, headerLength, nonce, 0, NONCE_LENGTH);
        int cipherOffset = headerLength + NONCE_LENGTH;

        try {
            // ── semgrep gcm-detection, decrypt path ────────────────────────────────────
            // Nothing here generates a nonce: it is read back out of the stored envelope at
            // `System.arraycopy(envelope, headerLength, nonce, 0, NONCE_LENGTH)` above, so the
            // rule's concern (nonce reuse at encryption time) cannot arise on this path. See the
            // encrypt path for the generation evidence. Bare form scopes this to the next line only.
            // nosemgrep
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // Same finding, second match location; same reasoning as directly above.
            // nosemgrep
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] plaintext = cipher.doFinal(envelope, cipherOffset, envelope.length - cipherOffset);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException ex) {
            // Authentication failure: the ciphertext or the bound header was modified, or the key id
            // maps to the wrong key. Potential tampering evidence, so it stays distinguishable.
            throw new PayloadUnreadableException(
                    PayloadUnreadableException.AUTHENTICATION_FAILED,
                    "Exchange-audit payload failed authentication under key id '" + keyId
                            + "'; the stored bytes or their header do not match the tag",
                    ex);
        } catch (GeneralSecurityException ex) {
            throw new PayloadUnreadableException(
                    PayloadUnreadableException.MALFORMED_ENVELOPE,
                    "Exchange-audit payload could not be decrypted under key id '" + keyId + "'",
                    ex);
        }
    }

    /** The key id new payloads are sealed with. */
    @NonNull
    public String activeKeyId() {
        return activeKeyId;
    }

    /** Key ids this deployment can decrypt, active plus decrypt-only. */
    @NonNull
    public Set<String> readableKeyIds() {
        return keysById.keySet();
    }

    // ── Key plumbing ────────────────────────────────────────────────────────────────

    private static boolean isKeyRequired(@NonNull Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (KEY_REQUIRED_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String requireUsableKeyId(String configuredKeyId) {
        if (configuredKeyId == null || configuredKeyId.isBlank()) {
            throw new IllegalStateException("pos.supplier.audit.encryption.key-id must not be blank");
        }
        int length = configuredKeyId.getBytes(StandardCharsets.UTF_8).length;
        if (length > 255) {
            // The envelope encodes key id length in one unsigned byte.
            throw new IllegalStateException("pos.supplier.audit.encryption.key-id must be at most 255 bytes");
        }
        return configuredKeyId;
    }

    @NonNull
    private static SecretKey parseKey(@NonNull String keyId, @NonNull String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            // Never echo the value: it is key material even when malformed.
            throw new IllegalStateException("Exchange-audit encryption key '" + keyId + "' is not valid base64", ex);
        }
        if (raw.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException("Exchange-audit encryption key '" + keyId + "' must be " + AES_256_KEY_BYTES
                    + " bytes for AES-256 but decoded to " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    @NonNull
    private static Map<String, String> parsePreviousKeys(String configured) {
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
                        "pos.supplier.audit.encryption.previous-keys entries must be 'keyId:base64'");
            }
            parsed.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        }
        return parsed;
    }

    @NonNull
    private static SecretKey generateEphemeralKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(AES_256_KEY_BYTES * 8);
            return generator.generateKey();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate an ephemeral exchange-audit key", ex);
        }
    }

    @NonNull
    private static PayloadUnreadableException malformed(@NonNull String detail) {
        return new PayloadUnreadableException(
                PayloadUnreadableException.MALFORMED_ENVELOPE,
                "Exchange-audit payload envelope is malformed: " + detail);
    }
}
