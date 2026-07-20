package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.service.TaxProviderClient;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Selects the active {@link TaxProviderClient} from the test-mode flag (story T6).
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

    public TaxProviderSelector(
            TaxProperties properties, TestModeTaxProvider testModeProvider, ExternalTaxProvider externalProvider) {
        this.properties = properties;
        this.testModeProvider = testModeProvider;
        this.externalProvider = externalProvider;
    }

    /**
     * The provider for the current mode: test-mode calculator when test mode is enabled,
     * otherwise the external provider.
     *
     * @return the active provider
     */
    @NonNull
    public TaxProviderClient select() {
        return properties.getTestMode().isEnabled() ? testModeProvider : externalProvider;
    }
}
