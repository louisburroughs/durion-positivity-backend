package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.ExtTaxRegistrationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 S32d item 1 (AW49): recovery under a regime is on only while a registration for it is in effect and its
 * country's configured currency is the functional currency. Fixture data only: CA / CAD with the GST_HST regime.
 */
@DisplayName("S32d input-tax recovery flags")
class InputTaxRecoveryFlagsTest {

    private static final LocalDate FROM = LocalDate.parse("2026-10-15");

    private final ExtTaxRegistrationRepository registrations = mock(ExtTaxRegistrationRepository.class);
    private final TaxProfileClient taxProfiles = mock(TaxProfileClient.class);

    private static ExtTaxRegistration registration(String regime, LocalDate from, LocalDate to) {
        return ExtTaxRegistration.builder()
                .registrationId(UUID.randomUUID())
                .countryCode("CA")
                .regime(regime)
                .registrationNumber("123456789RT0001")
                .jurisdictionCode("CA")
                .effectiveFrom(from)
                .effectiveTo(to)
                .aggregateVersion(1)
                .changedAt(Instant.EPOCH)
                .syncedAt(Instant.EPOCH)
                .build();
    }

    private InputTaxRecoveryFlags flags(String functionalCurrency) {
        return new InputTaxRecoveryFlags(registrations, taxProfiles, new LedgerCurrency(functionalCurrency));
    }

    private void caProfile() {
        when(taxProfiles.taxTypes("CA")).thenReturn(new TaxProfileClient.TaxTypes("CA", "CAD", List.of(), List.of()));
    }

    private void inEffect(LocalDate date, ExtTaxRegistration... rows) {
        when(registrations.findInEffectOn(date))
                .thenReturn(
                        List.of(rows).stream().filter(r -> r.inEffectOn(date)).toList());
    }

    @Test
    @DisplayName("AC 2: a CAD tenant with an in-effect GST_HST registration recovers under GST_HST")
    void cadTenantWithRegistrationRecovers() {
        caProfile();
        inEffect(FROM, registration("GST_HST", FROM, null));

        assertThat(flags("CAD").inputTaxRecovery(FROM, "GST_HST")).isTrue();
        assertThat(flags("CAD").inputTaxRecovery(FROM, "QST")).isFalse();
        assertThat(flags("CAD").anyEnabled(FROM)).isTrue();
    }

    @Test
    @DisplayName("AC 1 [M]: a USD tenant holding a CA registration recovers nothing (the currency guard)")
    void currencyGuard() {
        caProfile();
        inEffect(FROM, registration("GST_HST", FROM, null));

        assertThat(flags("USD").inputTaxRecovery(FROM, "GST_HST")).isFalse();
        assertThat(flags("USD").anyEnabled(FROM)).isFalse();
        assertThat(flags("USD").regimes(FROM))
                .singleElement()
                .satisfies(flag -> assertThat(flag.enabled()).isFalse());
    }

    @Test
    @DisplayName("AC 3 [M]: a registration from the 15th does not cover the 14th")
    void registrationDateBoundary() {
        caProfile();
        ExtTaxRegistration fromThe15th = registration("GST_HST", FROM, null);
        inEffect(FROM.minusDays(1), fromThe15th);
        inEffect(FROM, fromThe15th);

        assertThat(flags("CAD").inputTaxRecovery(FROM.minusDays(1), "GST_HST")).isFalse();
        assertThat(flags("CAD").inputTaxRecovery(FROM, "GST_HST")).isTrue();
    }

    @Test
    @DisplayName("AC 3: a tenant without a registration has no regime and asks pos-tax nothing")
    void noRegistration() {
        inEffect(FROM);
        when(registrations.findAllByOrderByCountryCodeAscRegimeAscEffectiveFromAsc())
                .thenReturn(List.of());

        assertThat(flags("CAD").inputTaxRecovery(FROM, "GST_HST")).isFalse();
        assertThat(flags("CAD").regimes(FROM)).isEmpty();
        verifyNoInteractions(taxProfiles);
    }

    @Test
    @DisplayName("AW49: a profile that cannot be read is never read as off")
    void unavailableProfileThrows() {
        inEffect(FROM, registration("GST_HST", FROM, null));
        when(taxProfiles.taxTypes("CA")).thenThrow(new TaxServiceUnavailableException("down"));

        assertThatThrownBy(() -> flags("CAD").inputTaxRecovery(FROM, "GST_HST"))
                .isInstanceOf(TaxServiceUnavailableException.class);
    }

    @Test
    @DisplayName("item 2: the settings rows list every registered regime, with the registration in effect")
    void regimesRows() {
        caProfile();
        ExtTaxRegistration ended = registration("QST", FROM.minusYears(1), FROM.minusDays(1));
        ExtTaxRegistration current = registration("GST_HST", FROM, null);
        when(registrations.findAllByOrderByCountryCodeAscRegimeAscEffectiveFromAsc())
                .thenReturn(List.of(current, ended));

        List<InputTaxRecoveryFlags.RegimeFlag> rows = flags("CAD").regimes(FROM);

        assertThat(rows).extracting(InputTaxRecoveryFlags.RegimeFlag::regime).containsExactly("GST_HST", "QST");
        assertThat(rows.get(0).enabled()).isTrue();
        assertThat(rows.get(0).registration()).isSameAs(current);
        assertThat(rows.get(1).enabled()).isFalse();
        assertThat(rows.get(1).registration()).isNull();
    }
}
