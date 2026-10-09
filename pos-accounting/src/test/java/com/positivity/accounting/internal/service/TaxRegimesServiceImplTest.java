package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.TaxRegimesResponse;
import com.positivity.accounting.internal.dto.TaxTypesReference;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 #2659: the configured tax regimes of a country, grouped from pos-tax's tax-types read. Every code is a
 * placeholder ({@code ZZ}, {@code ZZ_*}): nothing here, as in the code under test, names a real country or regime.
 */
@DisplayName("TaxRegimesServiceImpl — a country's configured regimes, their regions and tax types (#2659)")
class TaxRegimesServiceImplTest {

    private TaxCountry taxCountry;
    private TaxReferenceClient taxReference;
    private TaxRegimesService service;

    @BeforeEach
    void setUp() {
        taxCountry = mock(TaxCountry.class);
        taxReference = mock(TaxReferenceClient.class);
        when(taxCountry.code()).thenReturn("ZZ");
        service = new TaxRegimesServiceImpl(taxCountry, taxReference);
    }

    private static TaxTypesReference answer(
            String country, List<TaxTypesReference.TaxType> taxTypes, List<TaxTypesReference.Regime> regimes) {
        return new TaxTypesReference(country, "XTS", taxTypes, regimes, "STUB");
    }

    @Test
    @DisplayName("no countryCode: the tax country's regimes, in configured order, each with its regions and its tax"
            + " types; a tax type with no regime is not served")
    void taxCountryGroupedByRegime() {
        when(taxReference.taxTypes("ZZ"))
                .thenReturn(answer(
                        "ZZ",
                        List.of(
                                new TaxTypesReference.TaxType("ZZ_LEVY", "ZZ_REGIME_1", "COUNTRY", true),
                                new TaxTypesReference.TaxType("ZZ_REGIONAL", "ZZ_REGIME_1", "PROVINCE", true),
                                new TaxTypesReference.TaxType("ZZ_EXTRA", "ZZ_REGIME_2", "PROVINCE", true),
                                new TaxTypesReference.TaxType("ZZ_LOCAL", null, "CITY", false)),
                        List.of(
                                new TaxTypesReference.Regime("ZZ_REGIME_1", List.of()),
                                new TaxTypesReference.Regime("ZZ_REGIME_2", List.of("R1", "R2")))));

        TaxRegimesResponse response = service.regimes(null);

        assertThat(response.countryCode()).isEqualTo("ZZ");
        assertThat(response.source()).isEqualTo("STUB");
        assertThat(response.regimes())
                .containsExactly(
                        new TaxRegimesResponse.Regime(
                                "ZZ_REGIME_1",
                                List.of(),
                                List.of(
                                        new TaxRegimesResponse.TaxType("ZZ_LEVY", "COUNTRY"),
                                        new TaxRegimesResponse.TaxType("ZZ_REGIONAL", "PROVINCE"))),
                        new TaxRegimesResponse.Regime(
                                "ZZ_REGIME_2",
                                List.of("R1", "R2"),
                                List.of(new TaxRegimesResponse.TaxType("ZZ_EXTRA", "PROVINCE"))));
    }

    @Test
    @DisplayName("a given countryCode is asked of pos-tax and echoed; the tax country is not read")
    void givenCountry() {
        when(taxReference.taxTypes("QZ"))
                .thenReturn(answer(
                        "QZ",
                        List.of(new TaxTypesReference.TaxType("QZ_LEVY", "QZ_REGIME", "COUNTRY", false)),
                        List.of(new TaxTypesReference.Regime("QZ_REGIME", List.of()))));

        TaxRegimesResponse response = service.regimes("QZ");

        assertThat(response.countryCode()).isEqualTo("QZ");
        assertThat(response.regimes())
                .singleElement()
                .satisfies(regime -> assertThat(regime.taxTypes())
                        .containsExactly(new TaxRegimesResponse.TaxType("QZ_LEVY", "COUNTRY")));
        verify(taxCountry, never()).code();
    }

    @Test
    @DisplayName("a country pos-tax has no profile for answers an empty regimes list, not an error")
    void countryWithoutProfile() {
        when(taxReference.taxTypes("XA")).thenReturn(new TaxTypesReference("XA", null, List.of(), List.of(), "STUB"));

        TaxRegimesResponse response = service.regimes("XA");

        assertThat(response.countryCode()).isEqualTo("XA");
        assertThat(response.source()).isEqualTo("STUB");
        assertThat(response.regimes()).isEmpty();
    }

    @Test
    @DisplayName("ADR-0017: an answer missing a list, the source or a required field, or naming an undeclared regime,"
            + " is 503 and never read as no regimes")
    void unreadableAnswerIsUnavailable() {
        List<TaxTypesReference.Regime> regimes = List.of(new TaxTypesReference.Regime("ZZ_REGIME_1", List.of()));
        List<TaxTypesReference> unreadable = List.of(
                new TaxTypesReference("ZZ", null, List.of(), null, "STUB"),
                new TaxTypesReference("ZZ", null, null, List.of(), "STUB"),
                new TaxTypesReference("ZZ", null, List.of(), List.of(), null),
                answer("ZZ", List.of(), List.of(new TaxTypesReference.Regime(null, List.of()))),
                answer("ZZ", List.of(), List.of(new TaxTypesReference.Regime("ZZ_REGIME_1", null))),
                answer("ZZ", Arrays.asList((TaxTypesReference.TaxType) null), regimes),
                answer("ZZ", List.of(new TaxTypesReference.TaxType(null, "ZZ_REGIME_1", "COUNTRY", true)), regimes),
                answer("ZZ", List.of(new TaxTypesReference.TaxType("ZZ_LEVY", "ZZ_REGIME_1", null, true)), regimes),
                answer("ZZ", List.of(new TaxTypesReference.TaxType("ZZ_LEVY", "ZZ_OTHER", "COUNTRY", true)), regimes));

        for (TaxTypesReference answer : unreadable) {
            when(taxReference.taxTypes("ZZ")).thenReturn(answer);
            assertThatThrownBy(() -> service.regimes(null))
                    .as("%s", answer)
                    .isInstanceOf(TaxServiceUnavailableException.class)
                    .hasMessage("The tax service is unavailable");
        }
    }

    @Test
    @DisplayName("pos-tax unavailable propagates as 503")
    void posTaxUnavailable() {
        when(taxReference.taxTypes("ZZ"))
                .thenThrow(new TaxServiceUnavailableException("The tax service is unavailable"));

        assertThatThrownBy(() -> service.regimes(null)).isInstanceOf(TaxServiceUnavailableException.class);
    }
}
