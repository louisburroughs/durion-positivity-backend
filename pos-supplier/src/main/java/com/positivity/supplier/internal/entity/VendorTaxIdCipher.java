package com.positivity.supplier.internal.entity;

import com.positivity.supplier.internal.exception.VendorTaxIdUnreadableException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of vendor tax-registration numbers at rest (#2621; Security ruling on #2617,
 * ruling 3). Every registration number is RESTRICTED whatever its scheme, so pos-supplier stores only this
 * cipher's output next to the masked {@code last4}.
 *
 * <ul>
 *   <li><strong>Same envelope and key policy as the exchange-audit cipher</strong>, through
 *       {@link AesGcmEnvelopeCipher}: AES-256-GCM, key id in the header, a random 96-bit nonce, decrypt-only
 *       previous keys, and startup fails without a key unless every active profile is {@code dev} or
 *       {@code test}.
 *   <li><strong>A separate key.</strong> {@code pos.supplier.vendor-tax-id.encryption.key}, bound from
 *       {@code SUPPLIER_VENDOR_TAXID_ENC_KEY}. The exchange-audit key is never read here: audit payloads live
 *       400 days, a number lives as long as its vendor, and the two keys rotate on different clocks.
 *   <li><strong>Bound to its row.</strong> The AAD is the envelope header followed by the tenant, vendor and
 *       registration ids, so a ciphertext copied into another registration, vendor or tenant fails
 *       authentication instead of revealing someone else's number under the wrong name.
 * </ul>
 *
 * <p>Ciphertext is stored as base64 inside the {@code tax_registrations} jsonb element
 * ({@code numberCiphertext}). Nothing here logs; failures carry the key id and failure kind only.
 */
@Component
public class VendorTaxIdCipher {

    /** The environment variable {@code application.yml} binds the key from; the failure message names it. */
    public static final String KEY_ENVIRONMENT_VARIABLE = "SUPPLIER_VENDOR_TAXID_ENC_KEY";

    private static final AesGcmEnvelopeCipher.Naming NAMING = new AesGcmEnvelopeCipher.Naming(
            "pos.supplier.vendor-tax-id.encryption",
            KEY_ENVIRONMENT_VARIABLE,
            "Vendor tax-registration number",
            "Vendor tax-id",
            "Security ruling on #2617, ruling 3");

    private final AesGcmEnvelopeCipher envelope;

    public VendorTaxIdCipher(
            @NonNull Environment environment,
            @Value("${pos.supplier.vendor-tax-id.encryption.key:}") String activeKeyBase64,
            @Value("${pos.supplier.vendor-tax-id.encryption.key-id:k1}") String configuredKeyId,
            @Value("${pos.supplier.vendor-tax-id.encryption.previous-keys:}") String previousKeys) {
        this.envelope = new AesGcmEnvelopeCipher(
                Objects.requireNonNull(environment, "environment must not be null"),
                activeKeyBase64,
                configuredKeyId,
                previousKeys,
                NAMING,
                (failure, keyId, message, cause) ->
                        new VendorTaxIdUnreadableException(failure.name(), keyId, message, cause));
    }

    /**
     * Seals a registration number for one registration of one vendor of one tenant.
     *
     * @return the envelope, base64
     */
    @NonNull
    public String seal(
            @NonNull UUID tenantId, @NonNull UUID vendorId, @NonNull UUID registrationId, @NonNull String number) {
        Objects.requireNonNull(number, "number must not be null");
        byte[] sealed =
                envelope.encrypt(number.getBytes(StandardCharsets.UTF_8), context(tenantId, vendorId, registrationId));
        return Base64.getEncoder().encodeToString(sealed);
    }

    /**
     * Opens a number sealed by {@link #seal} for the same tenant, vendor and registration.
     *
     * @throws VendorTaxIdUnreadableException when the stored value is not an envelope, names an unconfigured
     *     key, or fails authentication, including for another row's ids
     */
    @NonNull
    public String open(
            @NonNull UUID tenantId, @NonNull UUID vendorId, @NonNull UUID registrationId, @NonNull String sealed) {
        Objects.requireNonNull(sealed, "sealed must not be null");
        byte[] stored;
        try {
            stored = Base64.getDecoder().decode(sealed);
        } catch (IllegalArgumentException ex) {
            throw new VendorTaxIdUnreadableException(
                    AesGcmEnvelopeCipher.Failure.MALFORMED_ENVELOPE.name(),
                    null,
                    "Vendor tax-registration number envelope is malformed: not base64",
                    null);
        }
        return new String(
                envelope.decrypt(stored, context(tenantId, vendorId, registrationId)), StandardCharsets.UTF_8);
    }

    /** The key id new numbers are sealed with. */
    @NonNull
    public String activeKeyId() {
        return envelope.activeKeyId();
    }

    /** The bound context: fixed-width, unambiguous, and distinct from any exchange-audit AAD. */
    static byte @NonNull [] context(@NonNull UUID tenantId, @NonNull UUID vendorId, @NonNull UUID registrationId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        Objects.requireNonNull(registrationId, "registrationId must not be null");
        return ("pos-supplier/vendor-tax-id|tenant=" + tenantId + "|vendor=" + vendorId + "|registration="
                        + registrationId)
                .getBytes(StandardCharsets.UTF_8);
    }
}
