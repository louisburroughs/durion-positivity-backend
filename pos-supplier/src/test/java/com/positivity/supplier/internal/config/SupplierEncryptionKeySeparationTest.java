package com.positivity.supplier.internal.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #2621: the two pos-supplier keys may never be one key, on any launch path. */
@DisplayName("SupplierEncryptionKeySeparation (#2621)")
class SupplierEncryptionKeySeparationTest {

    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=";

    @Test
    @DisplayName("one key in both, padded or not, fails startup without echoing it")
    void refusesOneKeyForBoth() {
        assertThatThrownBy(() -> new SupplierEncryptionKeySeparation(KEY_A, KEY_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPPLIER_VENDOR_TAXID_ENC_KEY")
                .hasMessageNotContaining(KEY_A);
        assertThatThrownBy(() -> new SupplierEncryptionKeySeparation(KEY_A, " " + KEY_A.substring(0, 43) + " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("distinct keys, or either key absent (dev/test ephemeral), start")
    void acceptsDistinctOrAbsent() {
        assertThatCode(() -> new SupplierEncryptionKeySeparation(KEY_A, KEY_B)).doesNotThrowAnyException();
        assertThatCode(() -> new SupplierEncryptionKeySeparation("", "")).doesNotThrowAnyException();
        assertThatCode(() -> new SupplierEncryptionKeySeparation(KEY_A, null)).doesNotThrowAnyException();
    }
}
