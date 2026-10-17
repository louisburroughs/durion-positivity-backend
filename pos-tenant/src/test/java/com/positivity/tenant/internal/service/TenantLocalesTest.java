package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantLocales (#2618): BCP 47 with a region, listed, canonical case")
class TenantLocalesTest {

    private final TenantLocales locales = new TenantLocales(List.of(" en-US", "es-us", "fr-CA", "", "fr-FR "));

    @Test
    @DisplayName("the configured list is trimmed and canonicalised")
    void supported() {
        assertThat(locales.supported()).containsExactly("en-US", "es-US", "fr-CA", "fr-FR");
    }

    @Test
    @DisplayName("fr-ca is fr-CA; FR-fr is fr-FR; a bare language, an unlisted tag, blank and null are refused")
    void canonicalOf() {
        assertThat(locales.canonicalOf("fr-ca")).contains("fr-CA");
        assertThat(locales.canonicalOf(" FR-fr ")).contains("fr-FR");
        assertThat(locales.canonicalOf("en-US")).contains("en-US");
        assertThat(locales.canonicalOf("fr")).isEmpty();
        assertThat(locales.canonicalOf("de-DE")).isEmpty();
        assertThat(locales.canonicalOf("es-MX")).isEmpty();
        assertThat(locales.canonicalOf("")).isEmpty();
        assertThat(locales.canonicalOf(null)).isEmpty();
        assertThat(locales.canonicalOf("not a tag!")).isEmpty();
        assertThatThrownBy(() -> locales.canonical("fr")).isInstanceOf(IllegalArgumentException.class);
    }
}
