package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.Provider;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Selects the active {@link TaxProviderClient} (stories T6, T6-external).
 * <p>
 * The {@code test-mode.enabled} flag wins: when set, the internal test calculator is always
 * used. When test mode is disabled, {@code pos.tax.provider} chooses the concrete adapter —
 * {@link Provider#AVALARA} for the real Avalara AvaTax REST v2 adapter, otherwise the legacy
 * generic {@code EXTERNAL} stub (also the fallback for the default/unset
 * {@link Provider#TEST_MODE} value, preserving prior behavior).
 * <p>
 * Shared by {@code TaxCalculationServiceImpl} (estimate path) and
 * {@link TaxProviderLifecycleService} (commit/void/re-commit) so both always resolve the
 * same provider.
 */
@Component
public class TaxProviderSelector {

    private final TaxProperties properties;
    private final TestModeTaxProvider testModeProvider;
    private final ExternalTaxProvider externalProvider;
    private final AvalaraTaxProvider avalaraProvider;

    public TaxProviderSelector(
            TaxProperties properties,
            TestModeTaxProvider testModeProvider,
            ExternalTaxProvider externalProvider,
            AvalaraTaxProvider avalaraProvider) {
        this.properties = properties;
        this.testModeProvider = testModeProvider;
        this.externalProvider = externalProvider;
        this.avalaraProvider = avalaraProvider;
    }

    /**
     * The provider for the current mode: test-mode calculator when test mode is enabled;
     * otherwise the Avalara adapter or the legacy external stub per {@code pos.tax.provider}.
     *
     * @return the active provider
     */
    @NonNull
    public TaxProviderClient select() {
        if (properties.getTestMode().isEnabled()) {
            return testModeProvider;
        }
        return switch (properties.getProvider()) {
            case AVALARA -> avalaraProvider;
            // EXTERNAL and the default/unset TEST_MODE both resolve to the legacy stub.
            default -> externalProvider;
        };
    }
}
