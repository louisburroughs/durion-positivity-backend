package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceAmount;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Derives an invoice's AR state from accounting-owned facts (ADR-0044 R6, #842).
 *
 * <p>pos-invoice owns the invoice document (totals, lifecycle status) and feeds the
 * {@code ext_invoice} replica over {@code invoice.events.v1}. Accounting owns everything that
 * happens to the receivable afterwards — payment applications, reversals, credit memos — so the
 * balance due is computed here, never fetched from another service:
 *
 * <pre>balanceDue = total − (applied − reversed) − postedCreditMemos − appliedCustomerCredits − appliedDepositCredits</pre>
 *
 * <p>The {@code appliedCustomerCredits} term is the customer-credit draw-down (issue #992):
 * applying an open credit posts {@code Dr Customer Credit Liability / Cr AR}, so the receivable
 * really is settled and the balance must reflect it — otherwise a credit-settled invoice would
 * still read as outstanding and could be paid or credited twice.
 *
 * <p>The {@code appliedDepositCredits} term is the deposit-credit draw-down (issue #1652), decided
 * by analogy to ADR-0057 §6's contract-liability ruling ("Deposit-take invoices are excluded from
 * {@code invoiced}") and the #992 customer-credit treatment: the deposit-take
 * document is a contract-liability event ({@code Dr Cash / Cr Customer Deposit Liability}), and
 * the settlement invoice is the sale, so the deposit portion applied against it relieves that
 * liability rather than A/R — exactly as an applied customer credit (#992) relieves A/R via {@code
 * Dr Customer Credit Liability / Cr AR} rather than cash. A deposit-settled portion of a
 * settlement invoice is therefore never an outstanding receivable, and the balance must reflect
 * it or the deposit-settled amount would read as still collectible and be paid or credited twice.
 * The {@code ext_invoice_deposit_credit_application} replica carries only applied facts —
 * pos-invoice's {@code applyAvailableCredits()} applies a given deposit credit to a given invoice
 * at most once and publishes no reversal/void fact — so there is no reversal term today; if a
 * {@code payment.deposit-credit.reversed} fact is ever added, this calculator must subtract it
 * symmetrically to {@code reversed} above.
 */
@Component
@RequiredArgsConstructor
public class InvoiceBalanceCalculator {

    /** Lifecycle states (pos-invoice's) in which an invoice participates in AR. */
    public static final Set<String> AR_ELIGIBLE_STATUSES = Set.of("FINALIZED", "POSTED");

    private final ExtInvoiceRepository extInvoiceRepository;
    private final PaymentApplicationRepository paymentApplicationRepository;
    private final PaymentApplicationReversalRepository reversalRepository;
    private final CreditMemoRepository creditMemoRepository;
    private final CustomerCreditTransactionRepository creditTransactionRepository;
    private final ExtInvoiceDepositCreditApplicationRepository depositCreditApplicationRepository;

    public Optional<ExtInvoice> findInvoice(@NonNull UUID invoiceId) {
        return extInvoiceRepository.findById(invoiceId);
    }

    /**
     * {@code OLDEST_FIRST} order (#993): ascending {@link #oldestFirstKey}, invoices with no key
     * last, ties by invoice id. Payment allocation and the receivables worklist (#2502) share it.
     */
    public static final Comparator<ExtInvoice> OLDEST_FIRST = Comparator.comparing(
                    InvoiceBalanceCalculator::oldestFirstKey, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ExtInvoice::getInvoiceId);

    /**
     * The {@code OLDEST_FIRST} aging key (#993): the due date (as UTC start-of-day, aging is
     * calendar-based) when present, else the finalization instant — cheap defense against a
     * producer that projected no due date, not a transition mechanism.
     */
    @Nullable
    public static Instant oldestFirstKey(@NonNull ExtInvoice invoice) {
        if (invoice.getDueDate() != null) {
            return invoice.getDueDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return invoice.getFinalizedAt();
    }

    /** True when the replica's lifecycle status allows AR activity (payments, credits). */
    public boolean isArEligible(@NonNull ExtInvoice invoice) {
        return AR_ELIGIBLE_STATUSES.contains(invoice.getStatus());
    }

    /** Remaining amount due, derived from accounting's own application/credit records. */
    @NonNull
    public BigDecimal balanceDue(@NonNull ExtInvoice invoice) {
        UUID invoiceId = invoice.getInvoiceId();
        BigDecimal total = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
        BigDecimal applied = paymentApplicationRepository.sumAppliedAmountByInvoiceId(invoiceId);
        BigDecimal reversed = reversalRepository.sumReversedAmountByInvoiceId(invoiceId);
        BigDecimal credited =
                creditMemoRepository.sumCreditedAmountByInvoiceIdAndStatus(invoiceId, CreditMemoStatus.POSTED);
        BigDecimal creditApplied = creditTransactionRepository.sumAmountByInvoiceIdAndType(
                invoiceId, CustomerCreditTransactionType.APPLICATION);
        BigDecimal depositApplied = depositCreditApplicationRepository.sumAmountAppliedByInvoiceId(invoiceId);
        return total.subtract(applied)
                .add(reversed)
                .subtract(credited)
                .subtract(creditApplied)
                .subtract(depositApplied);
    }

    /**
     * Batch form of {@link #balanceDue}: the same formula for many invoices with one grouped query
     * per term — five per call, whatever the number of invoices (#2502, BR-3). Every invoice given
     * is in the result, keyed by id, in the order given.
     *
     * @param invoices replica rows to price; duplicates are ignored
     * @return balance due by invoice id
     */
    @NonNull
    public Map<UUID, BigDecimal> balancesDue(@NonNull Collection<ExtInvoice> invoices) {
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        for (ExtInvoice invoice : invoices) {
            balances.putIfAbsent(
                    invoice.getInvoiceId(), invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal());
        }
        if (balances.isEmpty()) {
            return balances;
        }
        List<UUID> ids = List.copyOf(balances.keySet());
        subtract(balances, paymentApplicationRepository.sumAppliedAmountByInvoiceIdIn(ids));
        for (InvoiceAmount reversed : reversalRepository.sumReversedAmountByInvoiceIdIn(ids)) {
            balances.computeIfPresent(reversed.invoiceId(), (id, balance) -> balance.add(reversed.amount()));
        }
        subtract(balances, creditMemoRepository.sumCreditedAmountByInvoiceIdInAndStatus(ids, CreditMemoStatus.POSTED));
        subtract(
                balances,
                creditTransactionRepository.sumAmountByInvoiceIdInAndType(
                        ids, CustomerCreditTransactionType.APPLICATION));
        subtract(balances, depositCreditApplicationRepository.sumAmountAppliedByInvoiceIdIn(ids));
        return balances;
    }

    private static void subtract(Map<UUID, BigDecimal> balances, List<InvoiceAmount> amounts) {
        for (InvoiceAmount amount : amounts) {
            balances.computeIfPresent(amount.invoiceId(), (id, balance) -> balance.subtract(amount.amount()));
        }
    }

    /**
     * Accounting's AR view of the invoice, derived from the balance: {@code PAID_IN_FULL} at
     * zero-or-below, {@code OPEN} when untouched, {@code PARTIALLY_PAID} in between. Used for AR
     * response payloads; the replica's own status stays pos-invoice's lifecycle.
     */
    @NonNull
    public InvoiceStatus deriveArStatus(@NonNull ExtInvoice invoice, @NonNull BigDecimal balanceDue) {
        BigDecimal total = invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal();
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            return InvoiceStatus.PAID_IN_FULL;
        }
        if (balanceDue.compareTo(total) < 0) {
            return InvoiceStatus.PARTIALLY_PAID;
        }
        return InvoiceStatus.OPEN;
    }
}
