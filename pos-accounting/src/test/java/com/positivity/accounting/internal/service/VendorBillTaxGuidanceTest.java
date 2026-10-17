package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bill read's tax-type choices and label source (CAP:550 #2679, AW51). Fixture codes only. */
@DisplayName("VendorBillTaxGuidance (#2679)")
class VendorBillTaxGuidanceTest {

    private static final LocalDate POSTING = LocalDate.of(2026, 10, 16);

    private final TaxProfileClient taxProfiles = mock(TaxProfileClient.class);
    private final TaxCountry taxCountry = mock(TaxCountry.class);
    private final InputTaxRecoveryFlags flags = mock(InputTaxRecoveryFlags.class);
    private final VendorBillPostingService postingService = mock(VendorBillPostingService.class);
    private final VendorBillTaxGuidance guidance =
            new VendorBillTaxGuidance(taxProfiles, taxCountry, flags, postingService);

    private static final TaxProfileClient.TaxTypes PROFILE = new TaxProfileClient.TaxTypes(
            "ZZ",
            "XTS",
            List.of(
                    new TaxProfileClient.TaxType("TAX_TYPE_1", "ZZ_REGIME_1", "COUNTRY", true, "Type one"),
                    new TaxProfileClient.TaxType("TAX_TYPE_2", "ZZ_REGIME_1", "PROVINCE", true),
                    new TaxProfileClient.TaxType("TAX_TYPE_3", "ZZ_REGIME_2", "PROVINCE", true, "Type three"),
                    new TaxProfileClient.TaxType("TAX_TYPE_4", null, "CITY", false)),
            List.of(
                    new TaxProfileClient.Regime("ZZ_REGIME_1", List.of(), "Regime one"),
                    new TaxProfileClient.Regime("ZZ_REGIME_2", List.of())));

    private static VendorBill bill(VendorBillStatus status, String tax) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setStatus(status);
        bill.setTaxAmount(new BigDecimal(tax));
        bill.setBillDate(POSTING.atStartOfDay());
        return bill;
    }

    private void recoveryOn(String... regimes) {
        when(taxCountry.code()).thenReturn("ZZ");
        when(postingService.postingDate(any()))
                .thenReturn(new VendorBillPostingService.PostingDate(POSTING, VendorBillPostingDateRule.BILL_DATE));
        when(flags.regimes(POSTING))
                .thenReturn(java.util.Arrays.stream(regimes)
                        .map(regime -> new InputTaxRecoveryFlags.RegimeFlag("ZZ", regime, true, null))
                        .toList());
    }

    @Test
    @DisplayName("AC3: an approvable bill that states tax lists the enabled regimes' tax types with their labels")
    void approvableBillListsEnabledRegimesTypes() {
        recoveryOn("ZZ_REGIME_1");

        List<VendorBillReview.TaxTypeChoice> choices =
                guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), Optional.of(PROFILE));

        assertThat(choices)
                .containsExactly(
                        new VendorBillReview.TaxTypeChoice("TAX_TYPE_1", "Type one"),
                        new VendorBillReview.TaxTypeChoice("TAX_TYPE_2", null));
    }

    @Test
    @DisplayName("AC3: recovery off, or a bill past approval, or one stating no tax: empty, and no tax call")
    void emptyWhenNotApplicable() {
        assertThat(guidance.choices(bill(VendorBillStatus.APPROVED, "12.00"), Optional.of(PROFILE))).isEmpty();
        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "0.00"), Optional.of(PROFILE)))
                .isEmpty();
        verifyNoInteractions(flags, postingService);

        recoveryOn();
        assertThat(guidance.choices(bill(VendorBillStatus.MATCH_EXCEPTION, "12.00"), Optional.of(PROFILE)))
                .isEmpty();
    }

    @Test
    @DisplayName("AC3: another country's enabled regime does not count")
    void otherCountryRegimeIgnored() {
        when(taxCountry.code()).thenReturn("ZZ");
        when(postingService.postingDate(any()))
                .thenReturn(new VendorBillPostingService.PostingDate(POSTING, VendorBillPostingDateRule.BILL_DATE));
        when(flags.regimes(POSTING))
                .thenReturn(List.of(new InputTaxRecoveryFlags.RegimeFlag("YY", "ZZ_REGIME_1", true, null)));

        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), Optional.of(PROFILE)))
                .isEmpty();
    }

    @Test
    @DisplayName("AC3: when the recovery flag or the profile cannot be read the choices are null: unknown, not empty")
    void nullWhenUnknown() {
        when(taxCountry.code()).thenReturn("ZZ");
        when(postingService.postingDate(any()))
                .thenReturn(new VendorBillPostingService.PostingDate(POSTING, VendorBillPostingDateRule.BILL_DATE));
        when(flags.regimes(POSTING)).thenThrow(new TaxServiceUnavailableException("down"));
        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), Optional.of(PROFILE)))
                .isNull();

        recoveryOn("ZZ_REGIME_1");
        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), Optional.empty()))
                .isNull();
    }

    @Test
    @DisplayName("the profile read degrades to empty when pos-tax does not answer; a label is never invented")
    void profileDegradesToEmpty() {
        when(taxCountry.code()).thenReturn("ZZ");
        when(taxProfiles.taxTypes("ZZ")).thenThrow(new TaxServiceUnavailableException("down"));
        assertThat(guidance.profile()).isEmpty();

        when(taxProfiles.taxTypes("ZZ")).thenReturn(PROFILE);
        assertThat(guidance.profile()).contains(PROFILE);
        assertThat(PROFILE.regimeLabel("ZZ_REGIME_1")).isEqualTo("Regime one");
        assertThat(PROFILE.regimeLabel("ZZ_REGIME_2")).isNull();
        assertThat(PROFILE.taxTypeLabel("TAX_TYPE_1")).isEqualTo("Type one");
        assertThat(PROFILE.taxTypeLabel("TAX_TYPE_9")).isNull();
    }
}
