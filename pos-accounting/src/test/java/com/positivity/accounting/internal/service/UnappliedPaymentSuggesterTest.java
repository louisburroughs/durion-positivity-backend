package com.positivity.accounting.internal.service;

import static com.positivity.accounting.internal.service.UnappliedPaymentSuggester.EXACT_TOTAL;
import static com.positivity.accounting.internal.service.UnappliedPaymentSuggester.REMITTANCE_REFERENCE;
import static com.positivity.accounting.internal.service.UnappliedPaymentSuggester.SAME_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.dto.PaymentMatchSuggestion;
import com.positivity.accounting.internal.dto.SuggestedInvoice;
import com.positivity.accounting.internal.service.UnappliedPaymentSuggester.OpenInvoice;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Suggestions A–D of #2502 (BR-4; spec P3), table-driven: which invoices, which reasons, which
 * amounts, and what is left over.
 */
@DisplayName("UnappliedPaymentSuggester (#2502)")
class UnappliedPaymentSuggesterTest {

    private static final UUID INV_1 = id(1);
    private static final UUID INV_2 = id(2);
    private static final UUID INV_3 = id(3);
    private static final UUID NOT_OPEN = id(9);

    private final UnappliedPaymentSuggester suggester = new UnappliedPaymentSuggester();

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199a000-0000-7000-8000-%012d", n));
    }

    private static OpenInvoice open(UUID id, String balance) {
        return new OpenInvoice(id, "INV-" + id.toString().substring(32), new BigDecimal(balance));
    }

    /**
     * Case name, remittance invoice, U, open invoices oldest first, expected reasons, expected
     * (invoice, suggested amount) pairs, expected left over.
     */
    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "A: remittance invoice open at exactly U (AC2)",
                        INV_2,
                        "4615.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "4615.00")),
                        List.of(REMITTANCE_REFERENCE, SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(INV_2, "4615.00"),
                        "0.00"),
                Arguments.of(
                        "A: remittance invoice owes less than U — its balance, the rest left over",
                        INV_1,
                        "150.00",
                        List.of(open(INV_1, "100.00")),
                        List.of(REMITTANCE_REFERENCE, SAME_CUSTOMER),
                        List.of(INV_1, "100.00"),
                        "50.00"),
                Arguments.of(
                        "A: remittance invoice owes more than U — capped at U",
                        INV_1,
                        "60.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "60.00")),
                        List.of(REMITTANCE_REFERENCE, SAME_CUSTOMER),
                        List.of(INV_1, "60.00"),
                        "0.00"),
                Arguments.of(
                        "A falls through when the remittance invoice is not open: B decides",
                        NOT_OPEN,
                        "200.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "200.00")),
                        List.of(SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(INV_2, "200.00"),
                        "0.00"),
                Arguments.of(
                        "B: exactly one invoice at U",
                        null,
                        "200.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "200.00"), open(INV_3, "50.00")),
                        List.of(SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(INV_2, "200.00"),
                        "0.00"),
                Arguments.of(
                        "C: the oldest two add up to U and no single invoice does (AC3)",
                        null,
                        "300.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "200.00")),
                        List.of(SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(INV_1, "100.00", INV_2, "200.00"),
                        "0.00"),
                Arguments.of(
                        "two exact runs: B does not apply; C takes the oldest two when they add up",
                        null,
                        "100.00",
                        List.of(
                                open(INV_1, "40.00"),
                                open(INV_2, "60.00"),
                                open(INV_3, "100.00"),
                                open(id(4), "100.00")),
                        List.of(SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(INV_1, "40.00", INV_2, "60.00"),
                        "0.00"),
                Arguments.of(
                        "two exact runs and no prefix: D",
                        null,
                        "100.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "100.00")),
                        List.of(SAME_CUSTOMER),
                        List.of(),
                        "100.00"),
                Arguments.of(
                        "C needs a prefix: a later pair adding up to U is not suggested",
                        null,
                        "250.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "200.00"), open(INV_3, "50.00")),
                        List.of(SAME_CUSTOMER),
                        List.of(),
                        "250.00"),
                Arguments.of(
                        "D: open invoices but no match (AC4)",
                        null,
                        "250.00",
                        List.of(open(INV_1, "100.00"), open(INV_2, "200.00")),
                        List.of(SAME_CUSTOMER),
                        List.of(),
                        "250.00"),
                Arguments.of(
                        "D: the customer has no open invoice — no reasons, the whole payment left over",
                        INV_1,
                        "80.00",
                        List.of(),
                        List.of(),
                        List.of(),
                        "80.00"),
                Arguments.of(
                        "rounding at currency scale: 100.0 and 100.00 are the same amount",
                        null,
                        "100.00",
                        List.of(open(INV_1, "100.0")),
                        List.of(SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(INV_1, "100.0"),
                        "0.00"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void suggests(
            String name,
            @Nullable UUID remittance,
            String unapplied,
            List<OpenInvoice> openInvoices,
            List<String> reasons,
            List<Object> expected,
            String leftOver) {

        PaymentMatchSuggestion suggestion = suggester.suggest(remittance, new BigDecimal(unapplied), openInvoices);

        assertThat(suggestion.getReasons()).containsExactlyElementsOf(reasons);
        List<UUID> ids = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        for (int i = 0; i < expected.size(); i += 2) {
            ids.add((UUID) expected.get(i));
            amounts.add(new BigDecimal((String) expected.get(i + 1)));
        }
        assertThat(suggestion.getInvoices())
                .extracting(SuggestedInvoice::getInvoiceId)
                .containsExactlyElementsOf(ids);
        for (int i = 0; i < amounts.size(); i++) {
            SuggestedInvoice invoice = suggestion.getInvoices().get(i);
            assertThat(invoice.getSuggestedAmount()).isEqualByComparingTo(amounts.get(i));
            assertThat(invoice.getSuggestedAmount()).isLessThanOrEqualTo(invoice.getBalanceDue());
            assertThat(invoice.getInvoiceNumber()).isNotBlank();
        }
        BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suggestion.getSuggestedTotal()).isEqualByComparingTo(total);
        assertThat(suggestion.getSuggestedTotal()).isLessThanOrEqualTo(new BigDecimal(unapplied));
        assertThat(suggestion.getLeftOver()).isEqualByComparingTo(leftOver);
        assertThat(suggestion.getLeftOver().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("identical data gives identical suggestions (BR-4)")
    void deterministic() {
        List<OpenInvoice> openInvoices = List.of(open(INV_1, "100.00"), open(INV_2, "200.00"));

        assertThat(suggester.suggest(null, new BigDecimal("300.00"), openInvoices))
                .isEqualTo(suggester.suggest(null, new BigDecimal("300.00"), openInvoices));
    }
}
