package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillTax;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillTaxRepository;
import com.positivity.accounting.internal.service.VendorBillTaxSplit.Withheld;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 S32d item 10 (ACs 8, 10, 11 and the bill half of AC 1): what a vendor bill's posting does with the tax its
 * document states. The country, regimes and tax types are fixture data standing in for a configured profile (the CAD
 * data set: GST and HST under GST_HST, QST under QST, PST under no regime and not recoverable); nothing here is named in
 * the code under test.
 */
@DisplayName("VendorBillTaxSplit: input-tax recovery on vendor bills (CAP:550 S32d item 10)")
class VendorBillTaxSplitTest {

    private static final String COUNTRY = "CA";
    private static final LocalDate BILL_DATE = LocalDate.of(2026, 10, 1);
    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b01");
    private static final UUID VENDOR_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b02");

    private final InputTaxRecoveryFlags flags = mock();
    private final TaxProfileClient taxProfiles = mock();
    private final VendorBillTaxRepository billTaxes = mock();
    private final ExtSupplierVendorRepository vendorCopies = mock();
    private final VendorBillTaxSplit split = new VendorBillTaxSplit(flags, taxProfiles, billTaxes, vendorCopies);

    private static TaxProfileClient.TaxTypes profile() {
        return new TaxProfileClient.TaxTypes(
                COUNTRY,
                "CAD",
                List.of(
                        new TaxProfileClient.TaxType("GST", "GST_HST", "FEDERAL", true),
                        new TaxProfileClient.TaxType("HST", "GST_HST", "FEDERAL", true),
                        new TaxProfileClient.TaxType("QST", "QST", "STATE", true),
                        new TaxProfileClient.TaxType("PST", null, "STATE", false)),
                List.of(
                        new TaxProfileClient.Regime("GST_HST", List.of()),
                        new TaxProfileClient.Regime("QST", List.of("QC"))));
    }

    private static TaxProfileClient.EvidenceRules evidence(String fromAmount) {
        return new TaxProfileClient.EvidenceRules(
                COUNTRY,
                BILL_DATE,
                "CAD",
                List.of(new TaxProfileClient.EvidenceRule(
                        "SUPPLIER_REGISTRATION_NUMBER",
                        new BigDecimal(fromAmount),
                        List.of("DRAWER_RECEIPT", "VENDOR_BILL"),
                        null,
                        null)),
                "GST_HST");
    }

    private static VendorBill bill(String gross, String tax) {
        VendorBill bill = new VendorBill(BILL_ID);
        bill.setVendorId(VENDOR_ID);
        bill.setBillNumber("INV-CA-1");
        bill.setBillDate(BILL_DATE.atStartOfDay());
        bill.setTotalAmount(new BigDecimal(gross));
        bill.setTaxAmount(tax == null ? null : new BigDecimal(tax));
        return bill;
    }

    private void states(Map<String, String> byType) {
        List<VendorBillTax> rows = new ArrayList<>();
        new java.util.TreeMap<>(byType)
                .forEach((type, amount) -> rows.add(VendorBillTax.of(
                        BILL_ID, type, new BigDecimal(amount), VendorBillTax.Source.DOCUMENT, Instant.EPOCH)));
        when(billTaxes.findByVendorBillIdOrderByTaxType(BILL_ID)).thenReturn(rows);
    }

    private void registered(String... regimes) {
        List<InputTaxRecoveryFlags.RegimeFlag> flagsOn = new ArrayList<>();
        for (String regime : regimes) {
            flagsOn.add(new InputTaxRecoveryFlags.RegimeFlag(COUNTRY, regime, true, null));
            when(flags.inputTaxRecovery(BILL_DATE, regime)).thenReturn(true);
        }
        when(flags.regimes(BILL_DATE)).thenReturn(flagsOn);
    }

    private void vendorHolds(String... schemes) {
        ExtSupplierVendor vendor = new ExtSupplierVendor();
        List<Map<String, String>> registrations = new ArrayList<>();
        for (String scheme : schemes) {
            registrations.add(Map.of("scheme", scheme, "region", "", "last4", "0001"));
        }
        vendor.setTaxRegistrations(registrations);
        when(vendorCopies.findById(VENDOR_ID)).thenReturn(Optional.of(vendor));
    }

    @BeforeEach
    void profileAnswers() {
        when(taxProfiles.taxTypes(COUNTRY)).thenReturn(profile());
        when(taxProfiles.evidenceRules(eq(COUNTRY), any())).thenReturn(evidence("100.00"));
        when(flags.inputTaxRecovery(any(), anyString())).thenReturn(false);
    }

    @Nested
    @DisplayName("A tenant without recovery (AC 1, bill half)")
    class WithoutRecovery {

        @Test
        @DisplayName("no regime on at the bill date -> Plan.NONE, and pos-tax is never asked")
        void usdTenantBooksTheGross() {
            when(flags.regimes(BILL_DATE)).thenReturn(List.of());

            VendorBillTaxSplit.Plan plan = split.plan(bill("1120.00", "120.00"));

            assertThat(plan).isSameAs(VendorBillTaxSplit.Plan.NONE);
            assertThat(plan.recoveredByKey()).isEmpty();
            assertThat(plan.automaticApprovalHold()).isEmpty();
            verifyNoInteractions(taxProfiles, billTaxes, vendorCopies);
        }

        @Test
        @DisplayName("a registration abroad whose flag is off (currency guard) recovers nothing")
        void registrationWithFlagOffIsNoRecovery() {
            when(flags.regimes(BILL_DATE))
                    .thenReturn(List.of(new InputTaxRecoveryFlags.RegimeFlag(COUNTRY, "GST_HST", false, null)));

            assertThat(split.plan(bill("1120.00", "120.00"))).isSameAs(VendorBillTaxSplit.Plan.NONE);
        }
    }

    @Nested
    @DisplayName("The split of a recovery-enabled tenant (AC 8)")
    class Split {

        @Test
        @DisplayName("GST 50 + PST 70: GST recovered to TAX_RECOVERABLE_GST_HST, PST not recoverable")
        void gstAndPst() {
            registered("GST_HST");
            vendorHolds("GST_HST");
            states(Map.of("GST", "50.00", "PST", "70.00"));

            VendorBillTaxSplit.Plan plan = split.plan(bill("1120.00", "120.00"));

            assertThat(plan.enabled()).isTrue();
            assertThat(plan.recoveredByKey())
                    .containsExactly(Map.entry("TAX_RECOVERABLE_GST_HST", new BigDecimal("50.00")));
            assertThat(plan.items())
                    .extracting(VendorBillTaxSplit.Item::taxType, VendorBillTaxSplit.Item::withheld)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("GST", null),
                            org.assertj.core.groups.Tuple.tuple("PST", Withheld.NOT_RECOVERABLE));
            assertThat(plan.automaticApprovalHold()).isEmpty();
        }

        @Test
        @DisplayName("HST 130 recovered under the same regime")
        void hst() {
            registered("GST_HST");
            vendorHolds("GST_HST");
            states(Map.of("HST", "130.00"));

            assertThat(split.plan(bill("1130.00", "130.00")).recoveredByKey())
                    .containsExactly(Map.entry("TAX_RECOVERABLE_GST_HST", new BigDecimal("130.00")));
        }

        @Test
        @DisplayName("GST + QST with a QST registration: both recovered, each to its regime's key")
        void gstAndQstRegistered() {
            registered("GST_HST", "QST");
            vendorHolds("GST_HST");
            states(Map.of("GST", "50.00", "QST", "99.75"));

            assertThat(split.plan(bill("1149.75", "149.75")).recoveredByKey())
                    .containsExactly(
                            Map.entry("TAX_RECOVERABLE_GST_HST", new BigDecimal("50.00")),
                            Map.entry("TAX_RECOVERABLE_QST", new BigDecimal("99.75")));
        }

        @Test
        @DisplayName("GST + QST without a QST registration: QST is NOT_REGISTERED and stays in the line's class")
        void gstAndQstUnregistered() {
            registered("GST_HST");
            vendorHolds("GST_HST");
            states(Map.of("GST", "50.00", "QST", "99.75"));

            VendorBillTaxSplit.Plan plan = split.plan(bill("1149.75", "149.75"));

            assertThat(plan.recoveredByKey())
                    .containsExactly(Map.entry("TAX_RECOVERABLE_GST_HST", new BigDecimal("50.00")));
            assertThat(plan.items())
                    .filteredOn(item -> "QST".equals(item.taxType()))
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.withheld()).isEqualTo(Withheld.NOT_REGISTERED);
                        assertThat(item.regime()).isEqualTo("QST");
                        assertThat(item.mappingKey()).isNull();
                    });
        }

        @Test
        @DisplayName("a credit note's negative amounts are split on their size")
        void creditNote() {
            registered("GST_HST");
            vendorHolds("GST_HST");
            states(Map.of("GST", "-5.00"));

            assertThat(split.plan(bill("-105.00", "-5.00")).recoveredByKey())
                    .containsExactly(Map.entry("TAX_RECOVERABLE_GST_HST", new BigDecimal("5.00")));
        }

        @Test
        @DisplayName("a tax type the profile does not declare is NOT_RECOVERABLE")
        void undeclaredType() {
            registered("GST_HST");
            states(Map.of("ECO_FEE", "10.00"));

            assertThat(split.plan(bill("110.00", "10.00")).items())
                    .singleElement()
                    .extracting(VendorBillTaxSplit.Item::withheld)
                    .isEqualTo(Withheld.NOT_RECOVERABLE);
        }

        @Test
        @DisplayName("a bill without tax recovers nothing and is not held")
        void noTax() {
            registered("GST_HST");

            VendorBillTaxSplit.Plan plan = split.plan(bill("100.00", "0.00"));

            assertThat(plan.enabled()).isTrue();
            assertThat(plan.items()).isEmpty();
            assertThat(plan.automaticApprovalHold()).isEmpty();
        }
    }

    @Nested
    @DisplayName("An unsplit bill (AC 10, AW51)")
    class Unsplit {

        @Test
        @DisplayName("a tax total with no tax by type -> TAX_SPLIT_MISSING, nothing recovered, automatic approval held")
        void noTypes() {
            registered("GST_HST");
            states(Map.of());

            VendorBillTaxSplit.Plan plan = split.plan(bill("1120.00", "120.00"));

            assertThat(plan.items()).singleElement().satisfies(item -> {
                assertThat(item.taxType()).isNull();
                assertThat(item.amount()).isEqualByComparingTo("120.00");
                assertThat(item.withheld()).isEqualTo(Withheld.TAX_SPLIT_MISSING);
            });
            assertThat(plan.recoveredByKey()).isEmpty();
            assertThat(plan.automaticApprovalHold()).contains(Withheld.TAX_SPLIT_MISSING);
        }

        @Test
        @DisplayName("typed amounts that do not add up to the stated tax are no split either")
        void typesDoNotAddUp() {
            registered("GST_HST");
            states(Map.of("GST", "50.00"));

            assertThat(split.plan(bill("1120.00", "120.00")).automaticApprovalHold())
                    .contains(Withheld.TAX_SPLIT_MISSING);
        }
    }

    @Nested
    @DisplayName("Evidence on bills (AC 11, AW53)")
    class Evidence {

        @Test
        @DisplayName("150.00 from a vendor whose copy holds no GST_HST registration -> SUPPLIER_REGISTRATION_MISSING,"
                + " held")
        void vendorWithoutRegistration() {
            registered("GST_HST");
            vendorHolds("QST");
            states(Map.of("GST", "7.14"));

            VendorBillTaxSplit.Plan plan = split.plan(bill("150.00", "7.14"));

            assertThat(plan.recoveredByKey()).isEmpty();
            assertThat(plan.items())
                    .singleElement()
                    .extracting(VendorBillTaxSplit.Item::withheld)
                    .isEqualTo(Withheld.SUPPLIER_REGISTRATION_MISSING);
            assertThat(plan.automaticApprovalHold()).contains(Withheld.SUPPLIER_REGISTRATION_MISSING);
        }

        @Test
        @DisplayName("a vendor not in the copy is missing its registration too")
        void vendorNotInCopy() {
            registered("GST_HST");
            when(vendorCopies.findById(VENDOR_ID)).thenReturn(Optional.empty());
            states(Map.of("GST", "7.14"));

            assertThat(split.plan(bill("150.00", "7.14")).automaticApprovalHold())
                    .contains(Withheld.SUPPLIER_REGISTRATION_MISSING);
        }

        @Test
        @DisplayName("under the threshold the rule does not apply: recovered without a registration")
        void underThreshold() {
            registered("GST_HST");
            vendorHolds();
            states(Map.of("GST", "4.60"));

            assertThat(split.plan(bill("99.99", "4.60")).recoveredByKey())
                    .containsEntry("TAX_RECOVERABLE_GST_HST", new BigDecimal("4.60"));
        }

        @Test
        @DisplayName("the threshold is inclusive")
        void atThreshold() {
            registered("GST_HST");
            vendorHolds();
            states(Map.of("GST", "4.76"));

            assertThat(split.plan(bill("100.00", "4.76")).automaticApprovalHold())
                    .contains(Withheld.SUPPLIER_REGISTRATION_MISSING);
        }

        @Test
        @DisplayName("a rule with no supplier regime configured cannot be met: withheld")
        void noSupplierRegime() {
            registered("GST_HST");
            vendorHolds("GST_HST");
            when(taxProfiles.evidenceRules(eq(COUNTRY), any()))
                    .thenReturn(new TaxProfileClient.EvidenceRules(
                            COUNTRY, BILL_DATE, "CAD", evidence("100.00").rules(), null));
            states(Map.of("GST", "7.14"));

            assertThat(split.plan(bill("150.00", "7.14")).automaticApprovalHold())
                    .contains(Withheld.SUPPLIER_REGISTRATION_MISSING);
        }
    }

    @Nested
    @DisplayName("pos-tax unavailable (AW49): never read as off")
    class Unavailable {

        @Test
        @DisplayName("a profile pos-tax cannot answer propagates; nothing is decided")
        void profileUnavailable() {
            registered("GST_HST");
            states(Map.of("GST", "50.00"));
            when(taxProfiles.taxTypes(COUNTRY))
                    .thenThrow(new TaxServiceUnavailableException("The tax configuration is unavailable"));

            assertThatThrownBy(() -> split.plan(bill("1050.00", "50.00")))
                    .isInstanceOf(TaxServiceUnavailableException.class);
        }

        @Test
        @DisplayName("an evidence rule pos-tax cannot answer propagates")
        void evidenceUnavailable() {
            registered("GST_HST");
            states(Map.of("GST", "50.00"));
            when(taxProfiles.evidenceRules(eq(COUNTRY), any()))
                    .thenThrow(new TaxServiceUnavailableException("The tax configuration is unavailable"));

            assertThatThrownBy(() -> split.plan(bill("1050.00", "50.00")))
                    .isInstanceOf(TaxServiceUnavailableException.class);
        }
    }

    @Test
    @DisplayName("the bill date, not today, decides the flags")
    void billDateDecides() {
        VendorBill bill = bill("1050.00", "50.00");
        bill.setBillDate(LocalDateTime.of(2026, 9, 14, 0, 0));
        when(flags.regimes(LocalDate.of(2026, 9, 14))).thenReturn(List.of());

        assertThat(split.plan(bill)).isSameAs(VendorBillTaxSplit.Plan.NONE);
    }
}
