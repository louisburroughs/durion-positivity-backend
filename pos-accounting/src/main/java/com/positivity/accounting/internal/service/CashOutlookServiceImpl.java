package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.CashOutlookResponse;
import com.positivity.accounting.internal.dto.CashOutlookResponse.BillsLane;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Figure;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Point;
import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.service.OpenVendorBills.Open;
import com.positivity.accounting.internal.service.OpenVendorBills.OpenBill;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CashOutlookService} over the cash accounts, the open invoices and the open vendor bills.
 *
 * <p><b>Start.</b> In the bank plus waiting to be deposited, both from {@link CashAccounts}; never drawers or the
 * float, never the cushion.
 *
 * <p><b>Money in.</b> Open receivables ({@link InvoiceBalanceCalculator#isOpenReceivable}) of customers other than the
 * CASH walk-in account, on their {@code dueDate}, else on the finalization day (the tenant accounting date of {@code
 * finalizedAt}) plus the customer's terms: {@code NET<n>} reads as n days; {@code DUE_ON_RECEIPT}, {@code Due on
 * Receipt}, no terms and unparsable terms read as the finalization day. An invoice already past due is never assumed
 * collected: it is {@code overdueIn}, not in the line.
 *
 * <p><b>Money out.</b> The open amount of each {@code APPROVED} bill on its real due date, else its estimate ({@link
 * EstimatedDueDateService}, flagged in {@code estimatedMoneyOut}); a bill already past due counts on the first day.
 * Bills not yet approved are {@code possibleOut}; credit notes are {@code vendorCredits}; both stay out of the line.
 * Nothing is written to a bill.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashOutlookServiceImpl implements CashOutlookService {

    /** Days after today that the bills lane's "due in 7 days" segment reaches. */
    static final int BILLS_SOON_DAYS = 7;

    private static final Pattern NET = Pattern.compile("(?i)net[_ ]?([0-9]{1,3})");

    private final CashAccounts cashAccounts;
    private final OpenVendorBills openVendorBills;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final ExtCustomerBillingRulesRepository billingRules;
    private final CashAndPayablesSettings settings;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;
    private final Clock clock;

    @Override
    public @NonNull CashOutlookResponse outlook(int days) {
        validate(days);
        return compute(days, cashAccounts.read());
    }

    @Override
    public @NonNull CashOutlookResponse outlook(int days, CashAccounts.@NonNull Components cash) {
        validate(days);
        return compute(days, cash);
    }

    private static void validate(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw InvalidRequestParameterException.forField("days", "must be from 1 to " + MAX_DAYS);
        }
    }

    private CashOutlookResponse compute(int days, CashAccounts.Components cash) {
        String currency = ledgerCurrency.code();
        LocalDate today = zoneResolver.today();
        BigDecimal start = scaled(
                CashAccounts.Components.total(cash.bank()).add(CashAccounts.Components.total(cash.waiting())),
                currency);

        BigDecimal[] moneyIn = zeros(days + 1);
        BigDecimal[] moneyOut = zeros(days + 1);
        BigDecimal[] estimatedOut = zeros(days + 1);

        // Money in: open invoices on their due dates; overdue ones apart.
        BigDecimal expectedIn = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        int overdueCount = 0;
        for (Receivable receivable : receivables(today, currency)) {
            if (receivable.dueDate().isBefore(today)) {
                overdueAmount = overdueAmount.add(receivable.balance());
                overdueCount++;
                continue;
            }
            long day = ChronoUnit.DAYS.between(today, receivable.dueDate());
            if (day <= days) {
                moneyIn[(int) day] = moneyIn[(int) day].add(receivable.balance());
                expectedIn = expectedIn.add(receivable.balance());
            }
        }

        // Money out: approved bills; the bills lane and the estimated figure are not limited to the horizon.
        Open open = openVendorBills.load();
        BigDecimal expectedOut = BigDecimal.ZERO;
        Lane overdue = new Lane();
        Lane dueSoon = new Lane();
        Lane dueLater = new Lane();
        Lane estimated = new Lane();
        Lane credits = new Lane();
        for (OpenBill bill : open.approved()) {
            if (bill.openAmount().signum() < 0) {
                credits.add(bill.openAmount().negate());
                continue;
            }
            LocalDate due = bill.dueDate();
            if (due == null) {
                continue; // an approved bill always has a real due date or an estimate; belt only
            }
            long day = due.isBefore(today) ? 0 : ChronoUnit.DAYS.between(today, due);
            if (day <= days) {
                moneyOut[(int) day] = moneyOut[(int) day].add(bill.openAmount());
                expectedOut = expectedOut.add(bill.openAmount());
                if (bill.estimated()) {
                    estimatedOut[(int) day] = estimatedOut[(int) day].add(bill.openAmount());
                }
            }
            if (bill.estimated()) {
                estimated.add(bill.openAmount());
            } else if (due.isBefore(today)) {
                overdue.add(bill.openAmount());
            } else if (ChronoUnit.DAYS.between(today, due) <= BILLS_SOON_DAYS) {
                dueSoon.add(bill.openAmount());
            } else {
                dueLater.add(bill.openAmount());
            }
        }
        Lane possible = new Lane();
        for (OpenBill bill : open.unapproved()) {
            if (bill.openAmount().signum() < 0) {
                credits.add(bill.openAmount().negate());
            } else {
                possible.add(bill.openAmount());
            }
        }

        // The projection.
        List<Point> points = new ArrayList<>(days + 1);
        BigDecimal balance = start;
        Point low = null;
        for (int day = 0; day <= days; day++) {
            balance = balance.add(moneyIn[day]).subtract(moneyOut[day]);
            Point point = new Point(
                    today.plusDays(day),
                    scaled(balance, currency),
                    scaled(moneyIn[day], currency),
                    scaled(moneyOut[day], currency),
                    scaled(estimatedOut[day], currency));
            points.add(point);
            if (low == null || point.balance().compareTo(low.balance()) < 0) {
                low = point;
            }
        }
        Optional<BigDecimal> cushion = settings.cashSafetyCushion();
        boolean below = cushion.isPresent() && low.balance().compareTo(cushion.get()) < 0;

        BillsLane lane = new BillsLane(
                overdue.figure(currency),
                dueSoon.figure(currency),
                dueLater.figure(currency),
                estimated.figure(currency),
                scaled(overdue.amount.add(dueSoon.amount).add(dueLater.amount).add(estimated.amount), currency));
        return new CashOutlookResponse(
                clock.instant(),
                true,
                currency,
                today,
                days,
                start,
                List.copyOf(points),
                low,
                scaled(expectedIn, currency),
                scaled(expectedOut, currency),
                possible.figure(currency),
                new Figure(scaled(overdueAmount, currency), overdueCount),
                credits.figure(currency),
                estimated.figure(currency),
                lane,
                cushion.map(value -> scaled(value, currency)).orElse(null),
                below);
    }

    /** An open, collectible-in-time invoice with its due date. */
    private record Receivable(
            @NonNull BigDecimal balance, @NonNull LocalDate dueDate) {}

    private List<Receivable> receivables(LocalDate today, String currency) {
        Set<UUID> walkIn = invoiceBalanceCalculator.walkInPartyIds();
        List<ExtInvoice> candidates =
                extInvoiceRepository.findByStatusIn(InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES).stream()
                        .filter(invoice -> !InvoiceBalanceCalculator.isWalkIn(invoice, walkIn))
                        .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> balances = invoiceBalanceCalculator.balancesDue(candidates);
        List<ExtInvoice> open = candidates.stream()
                .filter(invoice -> InvoiceBalanceCalculator.isOpenReceivable(
                        invoice, balances.getOrDefault(invoice.getInvoiceId(), BigDecimal.ZERO), currency))
                .toList();
        Map<UUID, String> termsByParty = termsOf(open);
        List<Receivable> receivables = new ArrayList<>(open.size());
        for (ExtInvoice invoice : open) {
            LocalDate due = invoice.getDueDate();
            if (due == null) {
                LocalDate finalized = invoice.getFinalizedAt() == null
                        ? InvoiceBalanceCalculator.receivableDocumentDate(invoice)
                        : zoneResolver.postingDate(invoice.getFinalizedAt());
                UUID party = partyOf(invoice);
                due = finalized.plusDays(termsDays(party == null ? null : termsByParty.get(party)));
            }
            receivables.add(new Receivable(
                    InvoiceBalanceCalculator.atCurrencyScale(balances.get(invoice.getInvoiceId()), currency), due));
        }
        return receivables;
    }

    /** The billing terms of the parties of the invoices that have no due date, in one query. */
    private Map<UUID, String> termsOf(List<ExtInvoice> invoices) {
        Set<UUID> parties = invoices.stream()
                .filter(invoice -> invoice.getDueDate() == null)
                .map(CashOutlookServiceImpl::partyOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> terms = new HashMap<>();
        if (!parties.isEmpty()) {
            for (ExtCustomerBillingRules rules : billingRules.findAllById(parties)) {
                terms.put(rules.getPartyId(), rules.getPaymentTerms());
            }
        }
        return terms;
    }

    private static @Nullable UUID partyOf(ExtInvoice invoice) {
        if (invoice.getPartyId() == null || invoice.getPartyId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(invoice.getPartyId().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The days a customer's terms stand for: {@code NET<n>} (also {@code Net 30}, {@code NET_30}) is n; due on receipt,
     * no terms and anything unparsable are 0, the finalization day.
     */
    static int termsDays(@Nullable String terms) {
        if (terms == null) {
            return 0;
        }
        Matcher net = NET.matcher(terms.trim());
        return net.matches() ? Integer.parseInt(net.group(1)) : 0;
    }

    private static BigDecimal[] zeros(int length) {
        BigDecimal[] values = new BigDecimal[length];
        Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static BigDecimal scaled(BigDecimal amount, String currency) {
        return InvoiceBalanceCalculator.atCurrencyScale(amount, currency);
    }

    /** A running amount and count. */
    private static final class Lane {
        private BigDecimal amount = BigDecimal.ZERO;
        private int count;

        void add(BigDecimal value) {
            amount = amount.add(value);
            count++;
        }

        Figure figure(String currency) {
            return new Figure(scaled(amount, currency), count);
        }
    }
}
