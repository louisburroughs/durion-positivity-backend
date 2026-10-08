package com.positivity.supplier.internal.config;

import java.util.Arrays;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the exchange-audit key and the vendor tax-id key are the same key (#2621; Security
 * ruling on #2617, ruling 3). The two protect data with different lifetimes and rotate on different clocks, so
 * one value in both would couple their compromise and their rotation.
 *
 * <p>A separate guard rather than a check inside either cipher, so neither cipher ever reads the other's key.
 * {@code deployment/alpha/deploy-backend.sh} refuses the same pair before a deploy; this covers every other
 * launch path. Keys are compared decoded, so padded and unpadded spellings of one key are caught; a key that
 * does not decode is left to its cipher, which reports it. The message never carries key material.
 */
@Component
public class SupplierEncryptionKeySeparation {

    public SupplierEncryptionKeySeparation(
            @Value("${pos.supplier.audit.encryption.key:}") @Nullable String auditKey,
            @Value("${pos.supplier.vendor-tax-id.encryption.key:}") @Nullable String vendorTaxIdKey) {
        byte[] audit = decode(auditKey);
        byte[] vendorTaxId = decode(vendorTaxIdKey);
        if (audit != null && vendorTaxId != null && Arrays.equals(audit, vendorTaxId)) {
            throw new IllegalStateException("SUPPLIER_VENDOR_TAXID_ENC_KEY must not equal SUPPLIER_AUDIT_ENC_KEY:"
                    + " each pos-supplier purpose needs its own key (openssl rand -base64 32)");
        }
    }

    private static byte @Nullable [] decode(@Nullable String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
