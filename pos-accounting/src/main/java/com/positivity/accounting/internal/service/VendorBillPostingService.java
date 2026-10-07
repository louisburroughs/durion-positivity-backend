package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a vendor bill or credit note once, at approval, in the approval's own transaction (CAP:550 S12, #2509;
 * SPEC-accounting-workspace §4.3 "Posting"; AW37-AW43), and reverses that posting when an approved bill is voided.
 * It follows {@link InvoiceRevenuePostingService}: accounts resolve through one posting category, {@value
 * #POSTING_CATEGORY}, and its mapping keys through {@link GLMappingResolver}, never by number; one {@code
 * vendor_bill_gl_posting} row per bill is the durable idempotency record (#2595).
 *
 * <p><b>The entry (AW39).</b> The credit is {@code ACCOUNTS_PAYABLE} (2000) for the billed gross. The debits follow
 * each line's class, or the whole bill's when its lines are not stored (an EDI bill):
 *
 * <ul>
 *   <li>{@link VendorBillDebitClass#RECEIPT_MATCHED} (a stocked line matched to its receipt): {@code
 *       GOODS_RECEIVED_NOT_BILLED} (2100) = billed quantity x received unit price, and {@code
 *       PURCHASE_PRICE_DIFFERENCE} (5050) = the rest of the billed line, debit or credit. A quantity difference stays
 *       open in 2100.
 *   <li>{@link VendorBillDebitClass#GOODS} (stock with no receipt behind it): 2100 at the billed net.
 *   <li>{@link VendorBillDebitClass#EXPENSE} (non-stock lines, services, supplies): the key {@code EXPENSE_<CODE>}
 *       the approver chose (the vendor default arrives with S24), else 422 {@code AP_BILL_UNCLASSIFIED}.
 *   <li>US tax, as stated and never recalculated, is part of the cost: into the expense of an expense line, into
 *       5050 for goods; never 2200, 1300 or 2100. A header tax on a bill with lines is prorated by line net, the
 *       residual cent on the largest line.
 * </ul>
 *
 * A credit note posts the mirror: Dr {@code ACCOUNTS_PAYABLE} / Cr its {@code EXPENSE_<CODE>} key ({@code EXPENSE})
 * or {@code PURCHASE_PRICE_DIFFERENCE} ({@code PRICE_ALLOWANCE}). A bill never debits 1300 (ADR-0048 §1). No phase-3
 * source states freight separately, so {@code FREIGHT_IN} (5060) is seeded for the template but no bill posts to it
 * yet.
 *
 * <p><b>The date (AW42).</b> The bill date when it is on or before the approval date and its period is open (neither
 * closed nor hard-locked); otherwise the approval date, both in the tenant's accounting calendar. The entry then goes
 * through the period gate on its own date: a closed period is 422 {@code PERIOD_CLOSED} unless the caller holds
 * {@code accounting:period:override} and gives a justification, a hard-locked one 422 {@code PERIOD_HARD_LOCKED}; a
 * missing or inactive mapping is refused too (#2601). Every refusal propagates, so the approval rolls back with it.
 *
 * <p><b>The void (AW42).</b> The entry is reversed through the journal-entry reversal (ADR-0047: linked both ways),
 * dated on the void date in that date's period, never back in the original period.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorBillPostingService {

    /** The posting category, and the journal entry source type, of every vendor-bill entry. */
    public static final String POSTING_CATEGORY = "VENDOR_BILL";

    static final String ACCOUNTS_PAYABLE_KEY = "ACCOUNTS_PAYABLE";
    static final String GOODS_RECEIVED_NOT_BILLED_KEY = "GOODS_RECEIVED_NOT_BILLED";
    static final String PURCHASE_PRICE_DIFFERENCE_KEY = "PURCHASE_PRICE_DIFFERENCE";

    /** Prefix of the {@code VENDOR_BILL} expense keys: {@code EXPENSE_<CODE>}, the nine AW18 codes. */
    public static final String EXPENSE_KEY_PREFIX = "EXPENSE_";

    /** Durable idempotency key of a bill's posting. */
    public static final String SOURCE_KEY_PREFIX = "VENDOR_BILL:";

    /** Durable idempotency key of an approved bill's void. */
    public static final String VOID_SOURCE_KEY_PREFIX = "VENDOR_BILL_VOID:";

    private static final int SCALE = 2;

    private final Clock clock;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;
    private final VendorBillGlPostingRepository postings;
    private final VendorBillLineRepository billLines;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final AccountingPeriodGate periodGate;
    private final LedgerCurrency ledgerCurrency;

    /** The approver's classification (AW39): the class of a bill without stored lines, and the expense key. */
    public record Classification(
            @Nullable VendorBillDebitClass debitClass,
            @Nullable String expenseMappingKey) {}

    /** The date an approval posts on, and why. */
    public record PostingDate(
            @NonNull LocalDate date, @NonNull VendorBillPostingDateRule rule) {}

    /** One debit (positive) or credit (negative) of the entry, by {@code VENDOR_BILL} mapping key. */
    record Leg(@NonNull String mappingKey, @NonNull BigDecimal signedAmount) {}

    /**
     * Posts {@code bill} for its approval. Joins the approval's transaction: any refusal propagates and rolls the
     * approval back with it (AW42).
     *
     * @param overrideJustification honoured by the period gate only for a holder of {@code accounting:period:override}
     * @return the posting row, entry and date included
     * @throws VendorBillException 409 {@code AP_BILL_NOT_APPROVABLE} when the bill is already posted, has nothing to
     *     post or is held for its currency; 422 {@code AP_BILL_UNCLASSIFIED} without a needed class; 400 {@code
     *     VALIDATION_ERROR} for a class the document cannot take
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull VendorBillGlPosting post(
            @NonNull VendorBill bill,
            @Nullable Classification classification,
            @Nullable String overrideJustification,
            @NonNull String actor) {
        UUID billId = bill.getVendorBillId();
        if (ledgerCurrency.isForeign(bill.getCurrency())) {
            // AW43: never posted at par. The status guard refuses CURRENCY_HOLD first; this holds the line for any
            // bill that escaped it.
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_APPROVABLE,
                    "Bill " + bill.getBillNumber() + " is in " + bill.getCurrency() + ", not the ledger currency "
                            + ledgerCurrency.code() + "; it is never posted at par");
        }
        UUID sourceEventId = sourceEventId(billId);
        if (postings.findByVendorBillId(billId).isPresent()
                || journalEntryService.findOriginalBySourceEvent(sourceEventId).isPresent()) {
            // The durable backstop behind the row lock and the status guard: a bill posts once (AW37).
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_APPROVABLE,
                    "Bill " + bill.getBillNumber() + " is already posted");
        }
        Classification effective = classification == null ? new Classification(null, null) : classification;
        List<Leg> legs = legs(bill, billLines.findByVendorBill_VendorBillIdOrderByLineNumber(billId), effective);

        PostingDate postingDate = postingDate(bill);
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        for (Leg leg : legs) {
            UUID account = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY, leg.mappingKey(), postingDate.date().atStartOfDay());
            lines.add(line(account, leg.signedAmount(), describe(leg.mappingKey(), bill)));
        }
        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(postingDate.date().atStartOfDay())
                .sourceEventId(sourceEventId)
                .sourceEventType(JournalEntrySourceTypes.VENDOR_BILL)
                .description(truncate("Vendor bill " + bill.getBillNumber() + vendorSuffix(bill), 500))
                .lines(lines)
                .build());
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        VendorBillGlPosting posting = new VendorBillGlPosting();
        posting.setVendorBillId(billId);
        posting.setSourceKey(SOURCE_KEY_PREFIX + billId);
        posting.setJournalEntryId(posted.getJournalEntryId());
        posting.setPostingDate(postingDate.date());
        posting.setPostingDateRule(postingDate.rule());
        posting.setDebitClass(effective.debitClass());
        posting.setExpenseMappingKey(effective.expenseMappingKey());
        posting.setGrossAmount(gross(bill));
        posting.setCurrencyCode(ledgerCurrency.code());
        posting.setPostedAt(Instant.now(clock));
        posting.setPostedBy(actor);
        VendorBillGlPosting saved = postings.saveAndFlush(posting);
        bill.setJournalEntryId(posted.getJournalEntryId());
        log.info(
                "Vendor bill {} posted at approval: entry {} ({}) dated {} ({}), gross {}",
                bill.getBillNumber(),
                posted.getEntryNumber(),
                posted.getJournalEntryId(),
                postingDate.date(),
                postingDate.rule(),
                saved.getGrossAmount());
        return saved;
    }

    /**
     * Reverses an approved bill's posting for its void (AW42): the mirror through the journal-entry reversal, dated
     * on the void date. Joins the void's transaction: a refusal (a closed or hard-locked void date) rolls it back.
     *
     * @return the posting row with its reversal recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull VendorBillGlPosting reverse(
            @NonNull VendorBill bill, @Nullable String overrideJustification, @NonNull String actor) {
        VendorBillGlPosting posting = postings.findByVendorBillId(bill.getVendorBillId())
                .orElseThrow(() -> new VendorBillException(
                        VendorBillException.Code.AP_BILL_NOT_VOIDABLE,
                        "Bill " + bill.getBillNumber() + " has no posting to reverse"));
        if (posting.getReversalJournalEntryId() != null) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_VOIDABLE,
                    "Bill " + bill.getBillNumber() + " is already voided");
        }
        // The void's own date, in that date's period: never back in the original period, even when it is open.
        LocalDate voidDate = zoneResolver.today();
        JournalEntryResponse reversal = journalEntryService.reverseJournalEntry(
                posting.getJournalEntryId(),
                truncate("Vendor bill " + bill.getBillNumber() + " voided", 200),
                voidDate,
                overrideJustification);
        posting.setReversalSourceKey(VOID_SOURCE_KEY_PREFIX + bill.getVendorBillId());
        posting.setReversalJournalEntryId(reversal.getJournalEntryId());
        posting.setReversalDate(voidDate);
        posting.setReversedAt(Instant.now(clock));
        posting.setReversedBy(actor);
        VendorBillGlPosting saved = postings.saveAndFlush(posting);
        log.info(
                "Vendor bill {} voided: entry {} reversed by {} dated {}",
                bill.getBillNumber(),
                posting.getJournalEntryId(),
                reversal.getEntryNumber(),
                voidDate);
        return saved;
    }

    /**
     * The date an approval today would post {@code bill} on (AW42): the bill date when it is on or before today and
     * its period is open, else today, in the tenant's accounting calendar.
     */
    public @NonNull PostingDate postingDate(@NonNull VendorBill bill) {
        LocalDate approvalDate = zoneResolver.today();
        LocalDate billDate = bill.getBillDate().toLocalDate();
        if (billDate.isAfter(approvalDate)) {
            return new PostingDate(approvalDate, VendorBillPostingDateRule.APPROVAL_DATE_BILL_DATE_FUTURE);
        }
        if (periodGate.isPostingBlocked(billDate)) {
            return new PostingDate(approvalDate, VendorBillPostingDateRule.APPROVAL_DATE_BILL_PERIOD_NOT_OPEN);
        }
        return new PostingDate(billDate, VendorBillPostingDateRule.BILL_DATE);
    }

    /** The source event of a bill's entry, derived from its durable key {@code VENDOR_BILL:<billId>}. */
    public static @NonNull UUID sourceEventId(@NonNull UUID billId) {
        return UUID.nameUUIDFromBytes((SOURCE_KEY_PREFIX + billId).getBytes(StandardCharsets.UTF_8));
    }

    // ---- the entry ------------------------------------------------------------------------------------------

    /**
     * The entry's legs by mapping key, summed per key, the credit last. Debits are positive, credits negative; they
     * net to zero, the billed gross on {@code ACCOUNTS_PAYABLE} (a residual cent of rounding goes to the largest
     * debit, so the payable is always the billed gross).
     */
    static @NonNull List<Leg> legs(
            @NonNull VendorBill bill, @NonNull List<VendorBillLine> lines, @NonNull Classification classification) {
        BigDecimal gross = gross(bill);
        if (gross.signum() == 0) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_APPROVABLE,
                    "Bill " + bill.getBillNumber() + " totals 0.00; there is nothing to approve or post");
        }
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        if (gross.signum() < 0) {
            creditNote(bill, gross.negate(), classification, debits);
        } else if (lines.isEmpty()) {
            headerOnly(bill, gross, classification, debits);
        } else {
            byLine(bill, lines, classification, debits);
        }
        balanceOnLargest(debits, gross);
        List<Leg> legs = new ArrayList<>();
        debits.forEach((key, amount) -> {
            if (amount.signum() != 0) {
                legs.add(new Leg(key, amount));
            }
        });
        legs.add(new Leg(ACCOUNTS_PAYABLE_KEY, gross.negate()));
        return legs;
    }

    /** A credit note (AW39): Dr accounts payable / Cr its class, tax included. */
    private static void creditNote(
            VendorBill bill, BigDecimal magnitude, Classification classification, Map<String, BigDecimal> debits) {
        VendorBillDebitClass debitClass = requireClass(bill, classification);
        switch (debitClass) {
            case EXPENSE -> add(debits, expenseKey(bill, classification), magnitude.negate());
            case PRICE_ALLOWANCE -> add(debits, PURCHASE_PRICE_DIFFERENCE_KEY, magnitude.negate());
            default ->
                throw new VendorBillException(
                        VendorBillException.Code.VALIDATION_ERROR,
                        "A credit note posts as EXPENSE or PRICE_ALLOWANCE, not " + debitClass);
        }
    }

    /** A bill whose lines are not stored (AW39): one class for the whole bill, the stated tax into its cost. */
    private static void headerOnly(
            VendorBill bill, BigDecimal gross, Classification classification, Map<String, BigDecimal> debits) {
        VendorBillDebitClass debitClass = requireClass(bill, classification);
        BigDecimal tax = scaled(bill.getTaxAmount()).abs();
        switch (debitClass) {
            case GOODS -> {
                add(debits, GOODS_RECEIVED_NOT_BILLED_KEY, gross.subtract(tax));
                add(debits, PURCHASE_PRICE_DIFFERENCE_KEY, tax);
            }
            case EXPENSE -> add(debits, expenseKey(bill, classification), gross);
            default ->
                throw new VendorBillException(
                        VendorBillException.Code.VALIDATION_ERROR,
                        "A bill without receipt-matched lines posts as GOODS or EXPENSE, not " + debitClass);
        }
    }

    /** A goods-receipt bill (AW39): each line by its own class, a header tax prorated by line net. */
    private static void byLine(
            VendorBill bill,
            List<VendorBillLine> lines,
            Classification classification,
            Map<String, BigDecimal> debits) {
        List<VendorBillLine> billed = new ArrayList<>();
        List<BigDecimal> nets = new ArrayList<>();
        for (VendorBillLine line : lines) {
            BigDecimal net = round(line.effectiveBilledQuantity().multiply(line.effectiveBilledUnitPrice()));
            if (net.signum() == 0) {
                continue; // received and not billed: the quantity stays open in 2100
            }
            billed.add(line);
            nets.add(net);
        }
        List<BigDecimal> taxShares = prorate(scaled(bill.getTaxAmount()).abs(), nets);
        for (int i = 0; i < billed.size(); i++) {
            VendorBillLine line = billed.get(i);
            BigDecimal net = nets.get(i);
            BigDecimal tax = taxShares.get(i);
            switch (classOf(line)) {
                case RECEIPT_MATCHED -> {
                    BigDecimal accrued = round(line.effectiveBilledQuantity().multiply(line.getUnitPrice()));
                    add(debits, GOODS_RECEIVED_NOT_BILLED_KEY, accrued);
                    add(
                            debits,
                            PURCHASE_PRICE_DIFFERENCE_KEY,
                            net.subtract(accrued).add(tax));
                }
                case GOODS -> {
                    add(debits, GOODS_RECEIVED_NOT_BILLED_KEY, net);
                    add(debits, PURCHASE_PRICE_DIFFERENCE_KEY, tax);
                }
                default -> add(debits, expenseKey(bill, classification), net.add(tax));
            }
        }
    }

    /** The class of a stored line: non-stock is EXPENSE, stock with a receipt behind it RECEIPT_MATCHED. */
    static @NonNull VendorBillDebitClass classOf(@NonNull VendorBillLine line) {
        if (!line.isInventoryItem()) {
            return VendorBillDebitClass.EXPENSE;
        }
        return line.getQuantity() != null && line.getQuantity().signum() > 0
                ? VendorBillDebitClass.RECEIPT_MATCHED
                : VendorBillDebitClass.GOODS;
    }

    /**
     * Splits {@code total} across {@code weights} in proportion (AW39 "prorated by line net"), each share rounded to
     * the cent and the residual cent on the largest weight, so the shares always sum to {@code total}.
     */
    static @NonNull List<BigDecimal> prorate(@NonNull BigDecimal total, @NonNull List<BigDecimal> weights) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal sum = weights.stream().map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0 || sum.signum() == 0) {
            weights.forEach(w -> shares.add(BigDecimal.ZERO.setScale(SCALE)));
            return shares;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        int largest = 0;
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal share = total.multiply(weights.get(i).abs()).divide(sum, SCALE, RoundingMode.HALF_UP);
            shares.add(share);
            allocated = allocated.add(share);
            if (weights.get(i).abs().compareTo(weights.get(largest).abs()) > 0) {
                largest = i;
            }
        }
        shares.set(largest, shares.get(largest).add(total.subtract(allocated)));
        return shares;
    }

    private static void balanceOnLargest(Map<String, BigDecimal> debits, BigDecimal gross) {
        BigDecimal sum = debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residual = gross.subtract(sum);
        if (residual.signum() == 0 || debits.isEmpty()) {
            return;
        }
        String largest = debits.entrySet().stream()
                .max(Comparator.comparing(e -> e.getValue().abs()))
                .map(Map.Entry::getKey)
                .orElseThrow();
        debits.merge(largest, residual, BigDecimal::add);
    }

    private static VendorBillDebitClass requireClass(VendorBill bill, Classification classification) {
        if (classification.debitClass() == null) {
            throw unclassified(bill);
        }
        return classification.debitClass();
    }

    private static String expenseKey(VendorBill bill, Classification classification) {
        String key = classification.expenseMappingKey();
        if (key == null || key.isBlank()) {
            throw unclassified(bill);
        }
        return key.trim();
    }

    private static VendorBillException unclassified(VendorBill bill) {
        return new VendorBillException(
                VendorBillException.Code.AP_BILL_UNCLASSIFIED,
                "Bill " + bill.getBillNumber() + " has no class and its vendor no default; give the approval a"
                        + " classification (debitClass, and expenseMappingKey for expenses)");
    }

    private static void add(Map<String, BigDecimal> debits, String key, BigDecimal amount) {
        debits.merge(key, amount, BigDecimal::add);
    }

    private static BigDecimal gross(VendorBill bill) {
        return scaled(bill.getTotalAmount());
    }

    private static BigDecimal scaled(@Nullable BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(SCALE) : round(amount);
    }

    private static BigDecimal round(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** A line of {@code signed} (debit when positive, credit when negative). */
    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID account, BigDecimal signed, String description) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(signed.signum() > 0 ? signed : BigDecimal.ZERO)
                .creditAmount(signed.signum() < 0 ? signed.negate() : BigDecimal.ZERO)
                .description(description)
                .build();
    }

    private static String describe(String mappingKey, VendorBill bill) {
        String what =
                switch (mappingKey) {
                    case ACCOUNTS_PAYABLE_KEY -> "Owed to vendor";
                    case GOODS_RECEIVED_NOT_BILLED_KEY -> "Goods received, now billed";
                    case PURCHASE_PRICE_DIFFERENCE_KEY -> "Purchase price difference and tax on goods";
                    default -> "Expense " + mappingKey.substring(Math.min(mappingKey.length(), 8));
                };
        return truncate(what + " - bill " + bill.getBillNumber(), 500);
    }

    private static String vendorSuffix(VendorBill bill) {
        return bill.getVendorName() == null || bill.getVendorName().isBlank() ? "" : " from " + bill.getVendorName();
    }

    private static String truncate(String text, int max) {
        return Objects.requireNonNull(text).length() <= max ? text : text.substring(0, max);
    }
}
