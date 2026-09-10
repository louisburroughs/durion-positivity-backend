package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformTenantTest {

    @Test
    void platformIdIsTheReservedZeroTenant() {
        assertThat(PlatformTenant.ID).isEqualTo(UUID.fromString("01900000-0000-7000-8000-000000000000"));
        assertThat(PlatformTenant.SLUG).isEqualTo("platform");
    }

    @Test
    void isPlatformMatchesOnlyTheConstant() {
        assertThat(PlatformTenant.isPlatform(PlatformTenant.ID)).isTrue();
        assertThat(PlatformTenant.isPlatform(UUID.fromString("01900000-0000-7000-8000-000000000001")))
                .isFalse();
        assertThat(PlatformTenant.isPlatform(null)).isFalse();
    }
}
