package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The module's one ISO 4217 check (ADR-0067 R-3, PC-4)")
class IsoCurrencyCodesTest {

    @Test
    @DisplayName("listed upper-case codes pass; unknown, lower-case, blank, null and wrong-length codes do not")
    void isoList() {
        assertThat(IsoCurrencyCodes.isIso("USD")).isTrue();
        assertThat(IsoCurrencyCodes.isIso("JPY")).isTrue();
        assertThat(IsoCurrencyCodes.isIso("XYZ")).isFalse();
        assertThat(IsoCurrencyCodes.isIso("usd")).isFalse();
        assertThat(IsoCurrencyCodes.isIso("   ")).isFalse();
        assertThat(IsoCurrencyCodes.isIso(null)).isFalse();
        assertThat(IsoCurrencyCodes.isIso("DOLLARS")).isFalse();
    }
}
