package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.ExtTaxRegistrationRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The input-tax recovery flags of the bound tenant (CAP:550 S32d item 1; AW49; SPEC-accounting-workspace §4.7).
 *
 * <p>{@code inputTaxRecovery(date, regime)} is true only when both hold on {@code date}:
 *
 * <ul>
 *   <li>a registration for {@code regime} is in effect in accounting's copy of pos-tax's registry ({@code
 *       ext_tax_registration}, S32c; both ends inclusive);
 *   <li>the tenant's functional currency equals the currency pos-tax configures for that registration's country
 *       (S32a's tax-types read). Nothing follows the currency code alone (§4.7), and nothing follows a registration
 *       alone: a USD tenant holding a registration abroad recovers nothing.
 * </ul>
 *
 * <p>Postings ask at their business date, so back-dating a registration never changes a posted entry. A country
 * whose profile cannot be read throws {@link TaxServiceUnavailableException}: the posting retries or holds, and the
 * flag is never read as "off". No country, regime or currency is named here.
 *
 * <p>The functional currency is the ledger currency until ADR-0067 A2 replicates a tenant's own ({@link
 * LedgerCurrency}, the one place pos-accounting reads it).
 */
@Component
@RequiredArgsConstructor
public class InputTaxRecoveryFlags {

    private final ExtTaxRegistrationRepository registrations;
    private final TaxProfileClient taxProfiles;
    private final LedgerCurrency ledgerCurrency;

    /**
     * Whether recovery under {@code regime} is on for the bound tenant on {@code date}.
     *
     * @throws TaxServiceUnavailableException when a registered country's profile cannot be read
     */
    public boolean inputTaxRecovery(@NonNull LocalDate date, @NonNull String regime) {
        return registrationFor(date, regime).isPresent();
    }

    /**
     * The registration that turns recovery under {@code regime} on, on {@code date}: in effect then, in a country
     * whose currency is the functional currency.
     *
     * @throws TaxServiceUnavailableException when a registered country's profile cannot be read
     */
    public @NonNull Optional<ExtTaxRegistration> registrationFor(@NonNull LocalDate date, @NonNull String regime) {
        Map<String, Boolean> currencyMatches = new HashMap<>();
        return registrations.findInEffectOn(date).stream()
                .filter(registration -> regime.equals(registration.getRegime()))
                .filter(registration -> currencyMatches.computeIfAbsent(
                        registration.getCountryCode(), this::countryUsesFunctionalCurrency))
                .findFirst();
    }

    /**
     * Every regime the bound tenant holds a registration for, with whether recovery under it is on on {@code date}:
     * the settings read's rows. Empty for a tenant without a registration (every USD tenant today).
     *
     * @throws TaxServiceUnavailableException when a registered country's profile cannot be read
     */
    public @NonNull List<RegimeFlag> regimes(@NonNull LocalDate date) {
        Map<String, Boolean> currencyMatches = new HashMap<>();
        Map<String, RegimeFlag> byRegime = new LinkedHashMap<>();
        for (ExtTaxRegistration registration :
                registrations.findAllByOrderByCountryCodeAscRegimeAscEffectiveFromAsc()) {
            String key = registration.getCountryCode() + "/" + registration.getRegime();
            boolean inEffect = registration.inEffectOn(date);
            boolean enabled = inEffect
                    && currencyMatches.computeIfAbsent(
                            registration.getCountryCode(), this::countryUsesFunctionalCurrency);
            RegimeFlag known = byRegime.get(key);
            if (known == null || (inEffect && known.registration() == null)) {
                byRegime.put(
                        key,
                        new RegimeFlag(
                                registration.getCountryCode(),
                                registration.getRegime(),
                                enabled,
                                inEffect ? registration : null));
            }
        }
        return List.copyOf(byRegime.values());
    }

    /**
     * Whether recovery under any regime is on for the bound tenant on {@code date}.
     *
     * @throws TaxServiceUnavailableException when a registered country's profile cannot be read
     */
    public boolean anyEnabled(@NonNull LocalDate date) {
        Map<String, Boolean> currencyMatches = new HashMap<>();
        return registrations.findInEffectOn(date).stream()
                .anyMatch(registration -> currencyMatches.computeIfAbsent(
                        registration.getCountryCode(), this::countryUsesFunctionalCurrency));
    }

    /** The functional currency, as this module knows it (ADR-0067 A2 replaces the source). */
    public @NonNull String functionalCurrency() {
        return ledgerCurrency.code();
    }

    private boolean countryUsesFunctionalCurrency(String countryCode) {
        String currency = taxProfiles.taxTypes(countryCode).currency();
        return currency != null && currency.equals(ledgerCurrency.code());
    }

    /**
     * One regime the tenant is registered for.
     *
     * @param countryCode the registration's country
     * @param regime the regime
     * @param enabled whether recovery under it is on on the date asked
     * @param registration the registration in effect on that date, or null when none is
     */
    public record RegimeFlag(
            @NonNull String countryCode,
            @NonNull String regime,
            boolean enabled,
            @Nullable ExtTaxRegistration registration) {}
}
