package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentMatchSuggestion;
import com.positivity.accounting.internal.dto.SuggestedInvoice;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Suggests the invoices an unapplied payment most likely pays, and says why (#2502, BR-4; spec P3).
 *
 * <p>The rules are tried in order over the payment customer's open invoices in {@code OLDEST_FIRST}
 * order, with U the payment's unapplied amount:
 *
 * <ol>
 *   <li>A. Remittance reference: the invoice the payment was taken against is open — suggest it with
 *       min(balance, U); reasons {@code REMITTANCE_REFERENCE}, {@code SAME_CUSTOMER}, plus
 *       {@code EXACT_TOTAL} when its balance equals U.
 *   <li>B. Exactly one open invoice has a balance equal to U — suggest it; reasons
 *       {@code SAME_CUSTOMER}, {@code EXACT_TOTAL}.
 *   <li>C. The first k ≥ 2 invoices add up exactly to U — suggest them with their balances; reasons
 *       {@code SAME_CUSTOMER}, {@code EXACT_TOTAL}.
 *   <li>D. Nothing is suggested; reason {@code SAME_CUSTOMER} when the customer has open invoices,
 *       none when not.
 * </ol>
 *
 * <p>A suggested amount never exceeds the invoice's balance and the suggested total never exceeds U.
 * The suggester is a pure function of its inputs: identical data gives identical suggestions. Amounts
 * are compared as given; callers pass them at currency scale.
 */
@Component
public class UnappliedPaymentSuggester {

    /** The payment was taken against the suggested invoice. */
    public static final String REMITTANCE_REFERENCE = "REMITTANCE_REFERENCE";

    /** The suggested invoices belong to the payment's own customer. */
    public static final String SAME_CUSTOMER = "SAME_CUSTOMER";

    /** The suggested balances add up exactly to the unapplied amount. */
    public static final String EXACT_TOTAL = "EXACT_TOTAL";

    /** An open invoice of the payment's customer, as the suggestion sees it. */
    public record OpenInvoice(
            @NonNull UUID invoiceId,
            @Nullable String invoiceNumber,
            @NonNull BigDecimal balanceDue) {}

    /**
     * @param sourceInvoiceId the invoice the payment was taken against, when known
     * @param unapplied       U, the payment's unapplied amount
     * @param openInvoices    the payment customer's open invoices, oldest first
     * @return the suggestion; never null
     */
    @NonNull
    public PaymentMatchSuggestion suggest(
            @Nullable UUID sourceInvoiceId, @NonNull BigDecimal unapplied, @NonNull List<OpenInvoice> openInvoices) {

        if (unapplied.signum() > 0) {
            // A. Remittance reference.
            if (sourceInvoiceId != null) {
                for (OpenInvoice invoice : openInvoices) {
                    if (invoice.invoiceId().equals(sourceInvoiceId)) {
                        BigDecimal amount = invoice.balanceDue().min(unapplied);
                        List<String> reasons = new ArrayList<>(List.of(REMITTANCE_REFERENCE, SAME_CUSTOMER));
                        if (invoice.balanceDue().compareTo(unapplied) == 0) {
                            reasons.add(EXACT_TOTAL);
                        }
                        return suggestion(reasons, List.of(suggested(invoice, amount)), unapplied);
                    }
                }
            }

            // B. Exactly one invoice for the whole amount.
            List<OpenInvoice> exact = openInvoices.stream()
                    .filter(invoice -> invoice.balanceDue().compareTo(unapplied) == 0)
                    .toList();
            if (exact.size() == 1) {
                OpenInvoice invoice = exact.getFirst();
                return suggestion(
                        List.of(SAME_CUSTOMER, EXACT_TOTAL),
                        List.of(suggested(invoice, invoice.balanceDue())),
                        unapplied);
            }

            // C. The oldest k >= 2 invoices add up exactly to the amount.
            BigDecimal running = BigDecimal.ZERO;
            for (int k = 0; k < openInvoices.size(); k++) {
                running = running.add(openInvoices.get(k).balanceDue());
                int compared = running.compareTo(unapplied);
                if (compared > 0) {
                    break;
                }
                if (compared == 0 && k >= 1) {
                    List<SuggestedInvoice> invoices = openInvoices.subList(0, k + 1).stream()
                            .map(invoice -> suggested(invoice, invoice.balanceDue()))
                            .toList();
                    return suggestion(List.of(SAME_CUSTOMER, EXACT_TOTAL), invoices, unapplied);
                }
            }
        }

        // D. Nothing to suggest.
        return suggestion(openInvoices.isEmpty() ? List.of() : List.of(SAME_CUSTOMER), List.of(), unapplied);
    }

    private static SuggestedInvoice suggested(OpenInvoice invoice, BigDecimal amount) {
        return SuggestedInvoice.builder()
                .invoiceId(invoice.invoiceId())
                .invoiceNumber(invoice.invoiceNumber())
                .balanceDue(invoice.balanceDue())
                .suggestedAmount(amount)
                .build();
    }

    private static PaymentMatchSuggestion suggestion(
            List<String> reasons, List<SuggestedInvoice> invoices, BigDecimal unapplied) {
        BigDecimal total = invoices.stream()
                .map(SuggestedInvoice::getSuggestedAmount)
                .reduce(BigDecimal.ZERO.setScale(unapplied.scale()), BigDecimal::add);
        return PaymentMatchSuggestion.builder()
                .reasons(List.copyOf(reasons))
                .invoices(invoices)
                .suggestedTotal(total)
                .leftOver(unapplied.subtract(total))
                .build();
    }
}
