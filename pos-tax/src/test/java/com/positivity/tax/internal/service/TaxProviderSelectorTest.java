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
        return new TaxProviderSelector(
                props, testMode, external, avalara, new TaxCountryProfiles(props), java.time.Clock.systemUTC());
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

    @Test
    @DisplayName("S32a: lifecycle routing follows the logged plug-in, even one whose profile was removed")
    void lifecycleRoutingFollowsTheLoggedPlugin() {
        TaxProperties props = TaxProfileFixtures.bind(TaxProfileFixtures.MADE_UP_COUNTRY);
        TaxProviderSelector selector = selector(props);

        assertThat(selector.lifecycleProviderFor("ZZ_SELF")).isInstanceOf(SelfHostedTaxPlugin.class);
        assertThat(selector.lifecycleProviderFor("TEST_MODE")).isSameAs(testMode);
        TaxProviderClient retired = selector.lifecycleProviderFor("QQ_SELF");
        assertThat(retired).isInstanceOf(RetiredSelfHostedPlugin.class);
        assertThat(retired.providerName()).isEqualTo("QQ_SELF");
        java.util.UUID ref = java.util.UUID.randomUUID();
        assertThat(retired.commit(ref).status())
                .isEqualTo(com.positivity.tax.common.enums.TaxProviderTransactionStatus.COMMITTED);
        assertThat(retired.voidTransaction(ref).status())
                .isEqualTo(com.positivity.tax.common.enums.TaxProviderTransactionStatus.VOIDED);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> retired.estimate(new com.positivity.tax.common.dto.TaxCalculationRequest()))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> retired.refund(new com.positivity.tax.common.dto.TaxCalculationRequest(), ref))
                .isInstanceOf(IllegalStateException.class);
        assertThat(selector.isSelfHosted("ZZ_SELF")).isTrue();
        assertThat(selector.isSelfHosted("TEST_MODE")).isFalse();
        assertThat(selector.isSelfHosted(null)).isFalse();
    }
}
