package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.tax.internal.config.TaxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story T6-external: {@link TaxProviderSelector} routing. The test-mode flag wins; when it is
 * off, {@code pos.tax.provider} selects Avalara vs the legacy external stub, and the
 * default/unset value preserves prior (external stub) behavior.
 */
@DisplayName("TaxProviderSelector routing Tests")
class TaxProviderSelectorTest {

    private final TestModeTaxProvider testMode = mock(TestModeTaxProvider.class);
    private final ExternalTaxProvider external = mock(ExternalTaxProvider.class);
    private final AvalaraTaxProvider avalara = mock(AvalaraTaxProvider.class);

    private TaxProviderSelector selector(TaxProperties props) {
        return new TaxProviderSelector(props, testMode, external, avalara);
    }

    @Test
    @DisplayName("test-mode enabled always selects the test-mode provider")
    void testModeWins() {
        TaxProperties props = new TaxProperties();
        props.getTestMode().setEnabled(true);
        props.setProvider(TaxProperties.Provider.AVALARA);
        assertThat(selector(props).select()).isSameAs(testMode);
    }

    @Test
    @DisplayName("test-mode off + provider=AVALARA selects the Avalara adapter")
    void avalaraSelectedWhenTestModeOff() {
        TaxProperties props = new TaxProperties();
        props.getTestMode().setEnabled(false);
        props.setProvider(TaxProperties.Provider.AVALARA);
        assertThat(selector(props).select()).isSameAs(avalara);
    }

    @Test
    @DisplayName("test-mode off + provider=EXTERNAL selects the legacy external stub")
    void externalSelectedWhenTestModeOff() {
        TaxProperties props = new TaxProperties();
        props.getTestMode().setEnabled(false);
        props.setProvider(TaxProperties.Provider.EXTERNAL);
        assertThat(selector(props).select()).isSameAs(external);
    }

    @Test
    @DisplayName("test-mode off + default/unset provider preserves the legacy external stub")
    void defaultPreservesExternal() {
        TaxProperties props = new TaxProperties();
        props.getTestMode().setEnabled(false);
        // provider defaults to TEST_MODE (unset) -> legacy external stub when test-mode is off.
        assertThat(selector(props).select()).isSameAs(external);
    }
}
