package com.positivity.supplier.internal.entity;

import com.positivity.supplier.internal.exception.PayloadUnreadableException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
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
 * <h2>Key policy — an allowlist of where a key is OPTIONAL, never a list of where it is required</h2>
 *
 * A real key is <strong>mandatory unless {@code dev} or {@code test} is explicitly active</strong>.
 * Startup fails rather than quietly writing under an ephemeral key that vanishes on restart and takes
 * the audit trail with it.
 *
 * <p>The polarity is the whole control, and it was wrong once. This originally required a key only when
 * one of {@code prod|indus|alpha} was active, which meant an <strong>empty or unrecognised</strong>
 * profile set took the ephemeral branch — and that is the shape this repo actually ships:
 * {@code docker-compose.yml} sets no {@code SPRING_PROFILES_ACTIVE} for pos-supplier, as it does for
 * almost every service. The result was a deployment against real PostgreSQL that minted a random key per
 * JVM, sealed weeks of commercial exchanges with it, emitted one WARN, and made every payload
 * permanently unreadable on the next restart — reported afterwards as
 * {@code SUPPLIER_AUDIT_PAYLOAD_AUTHENTICATION_FAILED}, i.e. as possible tampering, for data the
 * deployment had destroyed itself.
 *
 * <p>A fail-closed control must not depend on deployment configuration being right. Enumerating where a
 * key is <em>optional</em> means every environment nobody thought about — a new profile name, a typo, no
 * profile at all — fails closed. Enumerating where it is <em>required</em> means all of them fail open.
 * {@code AuditPayloadCipherTest.KeyPolicy} covers the empty and unrecognised cases explicitly, because
 * they are the ones that bit.
 *
 * <p><strong>Every</strong> active profile must be optional, not merely one of them. The first inversion used
 * "no active profile is optional", which still failed open for {@code SPRING_PROFILES_ACTIVE=prod,dev} and for
 * any deployment profile setting {@code spring.profiles.include=dev} — a production JVM with an ephemeral key,
 * which is the exact outcome the inversion was for. A deployment profile always wins.
 *
 * <p>The key is bound from {@code SUPPLIER_AUDIT_ENC_KEY} in {@code application.yml}. That indirection is
 * deliberate and also once absent: nothing bound the property, so an operator who followed the failure
 * message and set {@code SUPPLIER_AUDIT_ENC_KEY} still started with an ephemeral key while believing the
 * key was provisioned. Relaxed binding would have resolved only
 * {@code POS_SUPPLIER_AUDIT_ENCRYPTION_KEY}. If the property name here ever changes, the yaml binding and
 * the message below must change with it.
 *
 * <p>Nonces are 96 random bits per message from {@code SecureRandom}. A nonce must never repeat under
 * one key: GCM nonce reuse leaks the XOR of plaintexts and enables forgery, so nonces are never
 * derived from a counter, a timestamp, or anything about the row.
 *
 * <p>The envelope and key-policy mechanics live in {@link AesGcmEnvelopeCipher}, shared with
 * {@link VendorTaxIdCipher} (#2621), so the two ciphers cannot drift apart. This class binds nothing beyond
 * the header as AAD, so existing payloads stay readable.
 */
@Component
public class AuditPayloadCipher {

    static final byte ENVELOPE_VERSION = AesGcmEnvelopeCipher.ENVELOPE_VERSION;
    static final int NONCE_LENGTH = AesGcmEnvelopeCipher.NONCE_LENGTH;
    static final int GCM_TAG_BITS = AesGcmEnvelopeCipher.GCM_TAG_BITS;

    private static final AesGcmEnvelopeCipher.Naming NAMING = new AesGcmEnvelopeCipher.Naming(
            "pos.supplier.audit.encryption",
            "SUPPLIER_AUDIT_ENC_KEY",
            "Exchange-audit payload",
            "Exchange-audit",
            "ADR-0050 §7");

    private final AesGcmEnvelopeCipher envelope;

    public AuditPayloadCipher(
            @NonNull Environment environment,
            @Value("${pos.supplier.audit.encryption.key:}") String activeKeyBase64,
            @Value("${pos.supplier.audit.encryption.key-id:k1}") String configuredKeyId,
            @Value("${pos.supplier.audit.encryption.previous-keys:}") String previousKeys) {
        this.envelope = new AesGcmEnvelopeCipher(
                Objects.requireNonNull(environment, "environment must not be null"),
                activeKeyBase64,
                configuredKeyId,
                previousKeys,
                NAMING,
                AuditPayloadCipher::unreadable);
    }

    /**
     * Encrypts a payload under the active key.
     *
     * @param plaintext the payload; may be empty but not {@code null}
     * @return the envelope described in the class javadoc
     */
    public byte @NonNull [] encrypt(@NonNull String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        return envelope.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decrypts an envelope produced by {@link #encrypt(String)}.
     *
     * @param stored the stored bytes
     * @return the payload
     * @throws PayloadUnreadableException when the envelope is malformed, names an unconfigured key,
     *     or fails authentication
     */
    @NonNull
    public String decrypt(byte @NonNull [] stored) {
        return new String(envelope.decrypt(stored), StandardCharsets.UTF_8);
    }

    /** The key id new payloads are sealed with. */
    @NonNull
    public String activeKeyId() {
        return envelope.activeKeyId();
    }

    /** Key ids this deployment can decrypt, active plus decrypt-only. */
    @NonNull
    public Set<String> readableKeyIds() {
        return envelope.readableKeyIds();
    }

    private static RuntimeException unreadable(
            AesGcmEnvelopeCipher.Failure failure, String keyId, String message, Throwable cause) {
        String code =
                switch (failure) {
                    case MALFORMED_ENVELOPE -> PayloadUnreadableException.MALFORMED_ENVELOPE;
                    case UNKNOWN_KEY_ID -> PayloadUnreadableException.UNKNOWN_KEY_ID;
                    case AUTHENTICATION_FAILED -> PayloadUnreadableException.AUTHENTICATION_FAILED;
                };
        return cause == null
                ? new PayloadUnreadableException(code, message)
                : new PayloadUnreadableException(code, message, cause);
    }
}
