package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bill read's tax-type choices (CAP:550 #2679, AW51), from the tax country alone. Fixture codes only. */
@DisplayName("VendorBillTaxGuidance (#2679)")
class VendorBillTaxGuidanceTest {

    private static final LocalDate POSTING = LocalDate.of(2026, 10, 16);
    private static final ExtTaxRegistration IN_EFFECT = ExtTaxRegistration.builder()
            .countryCode("ZZ")
            .regime("ZZ_REGIME_1")
            .registrationNumber("000000000")
            .effectiveFrom(LocalDate.of(2026, 1, 1))
            .build();

    private final TaxProfileReads profiles = mock(TaxProfileReads.class);
    private final TaxCountry taxCountry = mock(TaxCountry.class);
    private final InputTaxRecoveryFlags flags = mock(InputTaxRecoveryFlags.class);
    private final VendorBillPostingService postingService = mock(VendorBillPostingService.class);
    private final VendorBillTaxGuidance guidance = new VendorBillTaxGuidance(profiles, taxCountry, flags, postingService);

    private static TaxProfileClient.TaxTypes profile(String currency) {
        return new TaxProfileClient.TaxTypes(
                "ZZ",
                currency,
                List.of(
                        new TaxProfileClient.TaxType("TAX_TYPE_1", "ZZ_REGIME_1", "COUNTRY", true, "Type one"),
                        new TaxProfileClient.TaxType("TAX_TYPE_2", "ZZ_REGIME_1", "PROVINCE", true),
                        new TaxProfileClient.TaxType("TAX_TYPE_3", "ZZ_REGIME_2", "PROVINCE", true, "Type three"),
                        new TaxProfileClient.TaxType("TAX_TYPE_4", null, "CITY", false)),
                List.of(
                        new TaxProfileClient.Regime("ZZ_REGIME_1", List.of(), "Regime one"),
                        new TaxProfileClient.Regime("ZZ_REGIME_2", List.of())));
    }

    private static VendorBill bill(VendorBillStatus status, String tax) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setStatus(status);
        bill.setTaxAmount(new BigDecimal(tax));
        bill.setBillDate(POSTING.atStartOfDay());
        return bill;
    }

    private void registered(InputTaxRecoveryFlags.RegimeFlag... flagsOnDate) {
        when(taxCountry.code()).thenReturn("ZZ");
        when(flags.functionalCurrency()).thenReturn("XTS");
        when(postingService.postingDate(any()))
                .thenReturn(new VendorBillPostingService.PostingDate(POSTING, VendorBillPostingDateRule.BILL_DATE));
        when(flags.regimesUnchecked(POSTING)).thenReturn(List.of(flagsOnDate));
    }

    @Test
    @DisplayName("AC3: an approvable bill that states tax lists the registered regime's tax types with their labels")
    void approvableBillListsRegisteredRegimesTypes() {
        registered(new InputTaxRecoveryFlags.RegimeFlag("ZZ", "ZZ_REGIME_1", false, IN_EFFECT));

        List<VendorBillReview.TaxTypeChoice> choices =
                guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), () -> Optional.of(profile("XTS")));

        assertThat(choices)
                .containsExactly(
                        new VendorBillReview.TaxTypeChoice("TAX_TYPE_1", "Type one"),
                        new VendorBillReview.TaxTypeChoice("TAX_TYPE_2", null));
    }

    @Test
    @DisplayName("AC3: a bill past approval or stating no tax is empty without asking anything")
    void notApplicableAsksNothing() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<Optional<TaxProfileClient.TaxTypes>> counting = () -> {
            asked.incrementAndGet();
            return Optional.of(profile("XTS"));
        };
        assertThat(guidance.choices(bill(VendorBillStatus.APPROVED, "12.00"), counting)).isEmpty();
        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "0.00"), counting)).isEmpty();
        assertThat(asked).hasValue(0);
        verifyNoInteractions(flags, postingService);
    }

    @Test
    @DisplayName("AC3: no registration in effect for the tax country, or recovery off (another currency): empty")
    void noRegistrationOrRecoveryOffIsEmpty() {
        registered();
        assertThat(guidance.choices(bill(VendorBillStatus.MATCH_EXCEPTION, "12.00"), () -> Optional.of(profile("XTS"))))
                .isEmpty();

        registered(
                new InputTaxRecoveryFlags.RegimeFlag("YY", "ZZ_REGIME_1", true, IN_EFFECT),
                new InputTaxRecoveryFlags.RegimeFlag("ZZ", "ZZ_REGIME_2", false, null));
        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), () -> Optional.of(profile("XTS"))))
                .as("another country's registration, and a lapsed one of the tax country, do not count")
                .isEmpty();

        registered(new InputTaxRecoveryFlags.RegimeFlag("ZZ", "ZZ_REGIME_1", false, IN_EFFECT));
        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), () -> Optional.of(profile("ZZD"))))
                .as("the country's currency is not the functional currency: recovery off")
                .isEmpty();
    }

    @Test
    @DisplayName("AC3: a registration in effect but no profile to read: null, unknown rather than empty")
    void nullWhenTheProfileIsUnknown() {
        registered(new InputTaxRecoveryFlags.RegimeFlag("ZZ", "ZZ_REGIME_1", false, IN_EFFECT));

        assertThat(guidance.choices(bill(VendorBillStatus.AWAITING_APPROVAL, "12.00"), Optional::empty)).isNull();
    }

    @Test
    @DisplayName("the profile read goes through TaxProfileReads for the tax country")
    void profileComesFromTheCachedReads() {
        when(taxCountry.code()).thenReturn("ZZ");
        when(profiles.taxTypesIfAvailable("ZZ")).thenReturn(Optional.of(profile("XTS")));
        assertThat(guidance.profile()).isPresent();
        assertThat(profile("XTS").regimeLabel("ZZ_REGIME_1")).isEqualTo("Regime one");
        assertThat(profile("XTS").regimeLabel("ZZ_REGIME_2")).isNull();
        assertThat(profile("XTS").taxTypeLabel("TAX_TYPE_1")).isEqualTo("Type one");
        assertThat(profile("XTS").taxTypeLabel("TAX_TYPE_9")).isNull();
    }
}
