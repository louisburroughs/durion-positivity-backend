package com.positivity.domainevents.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantReadingAllowanceV1 (#2619): a tenant id, a tier code, an amount with its currency, an instant")
class TenantReadingAllowanceV1Test {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void validFact() {
        TenantReadingAllowanceV1 fact = new TenantReadingAllowanceV1(
                TENANT, "STANDARD", new TenantReadingAllowanceV1.MonthlyAllowance(new BigDecimal("20.00"), "USD"), AT);
        assertThat(fact.monthlyAllowance().amount()).isEqualByComparingTo("20.00");
        assertThat(TenantReadingAllowanceV1.EVENT_TYPE).isEqualTo("tenant.reading-allowance.changed");
        assertThat(TenantReadingAllowanceV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void refusals() {
        TenantReadingAllowanceV1.MonthlyAllowance twenty =
                new TenantReadingAllowanceV1.MonthlyAllowance(new BigDecimal("20.00"), "USD");
        assertThatThrownBy(() -> new TenantReadingAllowanceV1(null, "STANDARD", twenty, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantReadingAllowanceV1(TENANT, " ", twenty, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantReadingAllowanceV1(TENANT, "STANDARD", null, AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantReadingAllowanceV1(TENANT, "STANDARD", twenty, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantReadingAllowanceV1.MonthlyAllowance(new BigDecimal("-1"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantReadingAllowanceV1.MonthlyAllowance(BigDecimal.ONE, "usd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantReadingAllowanceV1.MonthlyAllowance(BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
