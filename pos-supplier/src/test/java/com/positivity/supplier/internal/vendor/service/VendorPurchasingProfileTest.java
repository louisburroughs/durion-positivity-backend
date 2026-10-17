package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The designation rule (#2712): exactly one enabled, non-sandbox profile, else no channel. */
@DisplayName("VendorPurchasingProfile.designated (#2712)")
class VendorPurchasingProfileTest {

    private static SupplierProfileEntity profile(String ref, boolean enabled, boolean sandbox) {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setSupplierRef(ref);
        profile.setEnabled(enabled);
        profile.setSandbox(sandbox);
        return profile;
    }

    @Test
    void oneEnabledNonSandboxProfileIsTheChannel() {
        assertThat(VendorPurchasingProfile.designated(List.of(profile("edi-main", true, false))))
                .isEqualTo("edi-main");
    }

    @Test
    void disabledAndSandboxProfilesDoNotCount() {
        assertThat(VendorPurchasingProfile.designated(List.of(
                        profile("edi-main", true, false), profile("edi-old", false, false), profile("edi-sb", true, true))))
                .isEqualTo("edi-main");
    }

    @Test
    void noneOrSeveralIsNoChannel() {
        assertThat(VendorPurchasingProfile.designated(List.of())).isNull();
        assertThat(VendorPurchasingProfile.designated(List.of(profile("edi-sb", true, true)))).isNull();
        assertThat(VendorPurchasingProfile.designated(
                        List.of(profile("edi-a", true, false), profile("edi-b", true, false))))
                .isNull();
    }
}
