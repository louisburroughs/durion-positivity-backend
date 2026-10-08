package com.positivity.supplier.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.migration.VendorTaxRegistrationEncryptionMigration;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #2621: the two pos-supplier ciphers never share key material, on any launch path, before V4 runs. */
@DisplayName("SupplierEncryptionKeySeparation (#2621)")
class SupplierEncryptionKeySeparationTest {

    private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String KEY_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=";
    private static final String KEY_C = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=";

    @Test
    @DisplayName("one active key in both, padded or not, fails startup without echoing it")
    void refusesOneKeyForBoth() {
        assertThatThrownBy(() -> new SupplierEncryptionKeySeparation(KEY_A, "", KEY_A, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPPLIER_VENDOR_TAXID_ENC_KEY")
                .hasMessageNotContaining(KEY_A);
        assertThatThrownBy(() -> new SupplierEncryptionKeySeparation(KEY_A, "", " " + KEY_A.substring(0, 43) + " ", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an active vendor key equal to a retired audit key fails, and the reverse")
    void refusesOverlapWithPreviousKeys() {
        assertThatThrownBy(() -> new SupplierEncryptionKeySeparation(KEY_A, "k0:" + KEY_C, KEY_C, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(KEY_C);
        assertThatThrownBy(() -> new SupplierEncryptionKeySeparation(KEY_A, "", KEY_B, "k0:" + KEY_A))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("distinct keys, or either key absent (dev/test ephemeral), start")
    void acceptsDistinctOrAbsent() {
        assertThatCode(() -> new SupplierEncryptionKeySeparation(KEY_A, "k0:" + KEY_C, KEY_B, ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> new SupplierEncryptionKeySeparation("", "", "", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> new SupplierEncryptionKeySeparation(KEY_A, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V4 depends on the guard, so the guard has run before a single number is sealed")
    void v4DependsOnTheGuard() {
        assertThat(Arrays.stream(VendorTaxRegistrationEncryptionMigration.class.getConstructors())
                        .map(Constructor::getParameterTypes)
                        .map(Arrays::asList))
                .singleElement()
                .satisfies(parameters -> assertThat(parameters)
                        .containsExactlyInAnyOrder(VendorTaxIdCipher.class, SupplierEncryptionKeySeparation.class));
    }
}
