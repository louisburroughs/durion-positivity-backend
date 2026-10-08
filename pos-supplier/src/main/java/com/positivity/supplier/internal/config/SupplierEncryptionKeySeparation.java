package com.positivity.supplier.internal.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the exchange-audit cipher and the vendor tax-id cipher share key material (#2621; Security
 * ruling on #2617, ruling 3). The two protect data with different lifetimes and rotate on different clocks, so one
 * key in both would couple their compromise and their rotation. Every key is compared, active and decrypt-only, in
 * both directions: an active vendor key may not equal a retired audit key either.
 *
 * <p>A separate guard rather than a check inside either cipher, so neither cipher ever reads the other's key. The
 * {@code V4} migration takes it as a dependency, so it has run before V4 seals a single number.
 * {@code deployment/alpha/deploy-backend.sh} refuses equal active keys before a deploy; this covers every launch
 * path. Keys are compared decoded, so padded and unpadded spellings of one key are caught; a value that does not
 * decode is left to its cipher, which reports it. The message never carries key material.
 */
@Component
public class SupplierEncryptionKeySeparation {

    public SupplierEncryptionKeySeparation(
            @Value("${pos.supplier.audit.encryption.key:}") @Nullable String auditKey,
            @Value("${pos.supplier.audit.encryption.previous-keys:}") @Nullable String auditPreviousKeys,
            @Value("${pos.supplier.vendor-tax-id.encryption.key:}") @Nullable String vendorTaxIdKey,
            @Value("${pos.supplier.vendor-tax-id.encryption.previous-keys:}") @Nullable
                    String vendorTaxIdPreviousKeys) {
        List<byte[]> audit = keys(auditKey, auditPreviousKeys);
        List<byte[]> vendorTaxId = keys(vendorTaxIdKey, vendorTaxIdPreviousKeys);
        for (byte[] auditMaterial : audit) {
            for (byte[] vendorMaterial : vendorTaxId) {
                if (Arrays.equals(auditMaterial, vendorMaterial)) {
                    throw new IllegalStateException("SUPPLIER_VENDOR_TAXID_ENC_KEY (or one of its previous keys) must"
                            + " not equal SUPPLIER_AUDIT_ENC_KEY or one of its previous keys: each pos-supplier"
                            + " purpose needs its own key material (openssl rand -base64 32)");
                }
            }
        }
    }

    /** The active key and every {@code keyId:base64} previous key that decodes. */
    private static List<byte[]> keys(@Nullable String active, @Nullable String previous) {
        List<byte[]> keys = new ArrayList<>();
        add(keys, active);
        if (previous != null && !previous.isBlank()) {
            for (String entry : previous.split(",")) {
                int separator = entry.indexOf(':');
                if (separator > 0) {
                    add(keys, entry.substring(separator + 1));
                }
            }
        }
        return keys;
    }

    private static void add(List<byte[]> keys, @Nullable String base64) {
        if (base64 == null || base64.isBlank()) {
            return;
        }
        try {
            keys.add(Base64.getDecoder().decode(base64.trim()));
        } catch (IllegalArgumentException ex) {
            // Reported by the owning cipher, which knows the property name; never echoed here.
        }
    }
}
