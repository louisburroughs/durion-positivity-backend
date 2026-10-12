package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.SourceInvoiceState;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The source-invoice rule automatic application (cases b, c, d, g) and the unapplied-payments list share
 * (#2563).
 */
@DisplayName("SourceInvoiceRule (#2563)")
class SourceInvoiceRuleTest {

    private static final UUID CUSTOMER = UUID.fromString("0199c000-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0199c000-0000-7000-8000-000000000002");

    private static ExtInvoice invoice(String status, String partyId) {
        ExtInvoice invoice = new ExtInvoice();
        invoice.setInvoiceId(UUID.fromString("0199c000-0000-7000-8000-000000000010"));
        invoice.setStatus(status);
        invoice.setPartyId(partyId);
        invoice.setTotal(new BigDecimal("100.00"));
        return invoice;
    }

    @Test
    @DisplayName("b: an invoice not replicated is NOT_FOUND")
    void notFound() {
        assertThat(SourceInvoiceRule.standing(null, CUSTOMER)).isEqualTo(SourceInvoiceState.NOT_FOUND);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"DRAFT", "VOIDED", "CANCELLED"})
    @DisplayName("c: an invoice that is not FINALIZED or POSTED is NOT_ELIGIBLE, before its party is looked at")
    void notEligible(String status) {
        assertThat(SourceInvoiceRule.standing(invoice(status, CUSTOMER.toString()), CUSTOMER))
                .isEqualTo(SourceInvoiceState.NOT_ELIGIBLE);
        assertThat(SourceInvoiceRule.standing(invoice(status, OTHER.toString()), CUSTOMER))
                .isEqualTo(SourceInvoiceState.NOT_ELIGIBLE);
    }

    @ParameterizedTest(name = "party \"{0}\"")
    @ValueSource(strings = {"0199c000-0000-7000-8000-000000000002", "", "  ", "CUST-00412"})
    @DisplayName("d: another customer's invoice, or one whose party is not a UUID, is OTHER_CUSTOMER")
    void otherCustomer(String partyId) {
        assertThat(SourceInvoiceRule.standing(invoice("POSTED", partyId), CUSTOMER))
                .isEqualTo(SourceInvoiceState.OTHER_CUSTOMER);
        assertThat(SourceInvoiceRule.standing(invoice("POSTED", null), CUSTOMER))
                .isEqualTo(SourceInvoiceState.OTHER_CUSTOMER);
    }

    @Test
    @DisplayName("the customer's FINALIZED or POSTED invoice is left to its balance, its party read as a UUID")
    void canTakeThePayment() {
        assertThat(SourceInvoiceRule.standing(invoice("FINALIZED", CUSTOMER.toString()), CUSTOMER))
                .isNull();
        assertThat(SourceInvoiceRule.standing(
                        invoice("POSTED", " " + CUSTOMER.toString().toUpperCase() + " "), CUSTOMER))
                .isNull();
    }

    @Test
    @DisplayName("g: no balance left at the currency's scale is PAID; anything above is OPEN")
    void byBalance() {
        ExtInvoice invoice = invoice("POSTED", CUSTOMER.toString());

        assertThat(SourceInvoiceRule.byBalance(invoice, new BigDecimal("0.00"), "USD"))
                .isEqualTo(SourceInvoiceState.PAID);
        assertThat(SourceInvoiceRule.byBalance(invoice, new BigDecimal("0.004"), "USD"))
                .isEqualTo(SourceInvoiceState.PAID);
        assertThat(SourceInvoiceRule.byBalance(invoice, new BigDecimal("-5.00"), "USD"))
                .isEqualTo(SourceInvoiceState.PAID);
        assertThat(SourceInvoiceRule.byBalance(invoice, new BigDecimal("0.005"), "USD"))
                .isEqualTo(SourceInvoiceState.OPEN);
        assertThat(SourceInvoiceRule.byBalance(invoice, new BigDecimal("40.00"), "USD"))
                .isEqualTo(SourceInvoiceState.OPEN);
    }
}
