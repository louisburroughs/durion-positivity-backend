package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.Provider;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Selects the {@link TaxProviderClient} that answers a request (stories T6, T6-external; CAP:550
 * S32a).
 * <p>
 * <b>Per-country default first (ADR-0071 §3, interim).</b> When {@code pos.tax.default-providers}
 * routes the destination country to a plug-in, that plug-in answers in every provider mode. Today
 * the only plug-ins are the configuration-driven self-hosted ones, one per profiled country
 * ({@link SelfHostedTaxPlugin}, id {@code <country>_SELF}).
 * <p>
 * <b>Deployment-wide switch otherwise, unchanged.</b> The {@code test-mode.enabled} flag wins: when
 * set, the internal test calculator is used. When test mode is disabled, {@code pos.tax.provider}
 * chooses the concrete adapter — {@link Provider#AVALARA} for the real Avalara AvaTax REST v2
 * adapter, otherwise the legacy generic {@code EXTERNAL} stub (also the fallback for the
 * default/unset {@link Provider#TEST_MODE} value, preserving prior behavior).
 * <p>
 * Shared by {@code TaxCalculationServiceImpl} (estimate path), {@code TaxRateLookupServiceImpl}
 * and {@link TaxProviderLifecycleService} (commit/void/re-commit).
 */
@Component
public class TaxProviderSelector {

    private final TaxProperties properties;
    private final TestModeTaxProvider testModeProvider;
    private final ExternalTaxProvider externalProvider;
    private final AvalaraTaxProvider avalaraProvider;
    private final TaxCountryProfiles profiles;
    private final Map<String, SelfHostedTaxPlugin> plugins;

    /** The ids the deployment-wide switch logs; every other logged id is a self-hosted plug-in. */
    private static final java.util.Set<String> SWITCH_PROVIDER_IDS = java.util.Set.of(
            TestModeTaxProvider.PROVIDER_NAME, ExternalTaxProvider.PROVIDER_NAME, AvalaraTaxProvider.PROVIDER_NAME);

    public TaxProviderSelector(
            TaxProperties properties,
            TestModeTaxProvider testModeProvider,
            ExternalTaxProvider externalProvider,
            AvalaraTaxProvider avalaraProvider,
            TaxCountryProfiles profiles,
            Clock clock) {
        this.properties = properties;
        this.testModeProvider = testModeProvider;
        this.externalProvider = externalProvider;
        this.avalaraProvider = avalaraProvider;
        this.profiles = profiles;
        Map<String, SelfHostedTaxPlugin> byId = new LinkedHashMap<>();
        profiles.all().values().forEach(profile -> {
            SelfHostedTaxPlugin plugin = new SelfHostedTaxPlugin(profile, clock);
            byId.put(plugin.providerName(), plugin);
        });
        this.plugins = Map.copyOf(byId);
    }

    /**
     * The deployment-wide provider: test-mode calculator when test mode is enabled; otherwise the
     * Avalara adapter or the legacy external stub per {@code pos.tax.provider}.
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

    /**
     * The provider for an address in {@code countryCode}: the plug-in its per-country default
     * names, in every provider mode, otherwise {@link #select()}.
     *
     * @param countryCode the destination country; may be {@code null}
     * @return the provider
     */
    @NonNull
    public TaxProviderClient selectFor(@Nullable String countryCode) {
        return pluginFor(countryCode).<TaxProviderClient>map(plugin -> plugin).orElseGet(this::select);
    }

    /**
     * The plug-in {@code pos.tax.default-providers} routes {@code countryCode} to.
     *
     * @param countryCode the destination country; may be {@code null}
     * @return the plug-in, or empty when the country keeps the deployment-wide switch
     */
    @NonNull
    public Optional<SelfHostedTaxPlugin> pluginFor(@Nullable String countryCode) {
        return profiles.defaultProvider(countryCode).map(plugins::get);
    }

    /**
     * The plug-in with this id, as recorded on the provider transaction log.
     *
     * @param providerName a provider label; may be {@code null}
     * @return the plug-in, or empty when the label names no plug-in
     */
    @NonNull
    public Optional<SelfHostedTaxPlugin> pluginById(@Nullable String providerName) {
        return providerName == null ? Optional.empty() : Optional.ofNullable(plugins.get(providerName));
    }

    /**
     * Whether a provider label names a self-hosted plug-in, configured or not: any logged id that is
     * not one of the deployment-wide switch providers ({@code TEST_MODE}, {@code EXTERNAL},
     * {@code AVALARA}).
     *
     * @param providerName a provider label from the transaction log; may be {@code null}
     * @return {@code true} for a self-hosted plug-in id
     */
    public boolean isSelfHosted(@Nullable String providerName) {
        // Classified against the known switch providers, not by a name suffix and not by the live
        // registry: a document logged under a self-hosted plug-in whose profile was later removed must
        // still reach a no-op lifecycle (RetiredSelfHostedPlugin), and renaming a switch provider
        // (e.g. ADR-0071 step 1, #2629) follows its PROVIDER_NAME constant automatically.
        return providerName != null
                && (plugins.containsKey(providerName) || !SWITCH_PROVIDER_IDS.contains(providerName));
    }

    /**
     * The provider that commits and voids a document whose log row names {@code providerName}: the
     * plug-in that priced it, never the deployment-wide switch. A self-hosted plug-in whose profile
     * has since been removed is still answered by its logged no-op commit and void, because those hold
     * no state; only a row priced by the switch falls back to {@link #select()}.
     *
     * @param providerName the provider label recorded on the transaction log
     * @return the lifecycle provider
     */
    @NonNull
    public TaxProviderClient lifecycleProviderFor(@NonNull String providerName) {
        Optional<SelfHostedTaxPlugin> plugin = pluginById(providerName);
        if (plugin.isPresent()) {
            return plugin.get();
        }
        return isSelfHosted(providerName) ? new RetiredSelfHostedPlugin(providerName) : select();
    }
}
