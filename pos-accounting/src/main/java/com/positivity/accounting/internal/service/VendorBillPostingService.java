package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillTaxRecovery;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillTaxRecoveryRepository;
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
import java.util.Optional;
import java.util.Set;
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
 * or {@code PURCHASE_PRICE_DIFFERENCE} ({@code PRICE_ALLOWANCE}). A bill never debits 1300 (ADR-0048 §1).
 *
 * <p><b>The vendor's own totals (AW47).</b> A bill with the vendor's header totals (EDI) debits its class at the
 * stated net, and the stated tax as above: GOODS is Dr 2100 net / Dr 5050 tax, EXPENSE is Dr {@code EXPENSE_<CODE>}
 * net + tax. Accounts payable is always the gross. A gap {@code gross - (net + tax)} within the rounding tolerance
 * ({@link VendorBillTotals}) goes on the largest debit and is kept as {@code roundingAdjustment}; a larger one posts
 * where the person deciding said ({@code difference}): FREIGHT to {@code FREIGHT_IN} (5060), GOODS to 2100, EXPENSE
 * to its key, PRICE_DIFFERENCE to 5050, a negative gap as a credit. Without that decision it is 422 {@code
 * AP_BILL_TOTALS_UNRECONCILED}.
 *
 * <p><b>The date (AW42).</b> The bill date when it is on or before the approval date and its period is open (neither
 * closed nor hard-locked); otherwise the approval date, both in the tenant's accounting calendar. The entry then goes
 * through the period gate on its own date: a closed period is 422 {@code PERIOD_CLOSED} unless the caller holds
 * {@code accounting:period:override} and gives a justification, a hard-locked one 422 {@code PERIOD_HARD_LOCKED}; a
 * missing or inactive mapping is refused too (#2601). Every refusal propagates, so the approval rolls back with it.
 *
 * <p><b>The void (AW42).</b> The entry is reversed through the journal-entry reversal (ADR-0047: linked both ways),
 * dated on the void date in that date's period, never back in the original period. Only the void reverses it: the
 * journal-entry endpoint refuses a bill's entry and its void's ({@link VendorBillReversalReaction}).
 *
 * <p><b>Mappings (#2601).</b> A key with no mapping effective on the posting date, or one whose account is not
 * active then, is one refusal, 422 {@code GL_MAPPING_NOT_CONFIGURED}, naming the category, the key and the posting
 * date and what to do next; the approval rolls back.
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
    static final String FREIGHT_IN_KEY = "FREIGHT_IN";

    /** Prefix of the {@code VENDOR_BILL} expense keys: {@code EXPENSE_<CODE>}, the nine AW18 codes. */
    public static final String EXPENSE_KEY_PREFIX = "EXPENSE_";

    /** Durable idempotency key of a bill's posting. */
    public static final String SOURCE_KEY_PREFIX = "VENDOR_BILL:";

    /** Durable idempotency key of an approved bill's void. */
    public static final String VOID_SOURCE_KEY_PREFIX = "VENDOR_BILL_VOID:";

    private static final int SCALE = 2;

    private final Clock clock;
    private final GLMappingResolver glMappingResolver;
    private final GLAccountService glAccountService;
    private final JournalEntryService journalEntryService;
    private final VendorBillGlPostingRepository postings;
    private final VendorBillLineRepository billLines;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final AccountingPeriodGate periodGate;
    private final LedgerCurrency ledgerCurrency;
    private final VendorBillTaxSplit taxSplit;
    private final VendorBillTaxRecoveryRepository taxRecoveries;

    /** The approver's classification (AW39): the class of a bill without stored lines, and the expense key. */
    public record Classification(
            @Nullable VendorBillDebitClass debitClass,
            @Nullable String expenseMappingKey) {}

    /** The date an approval posts on, and why. */
    public record PostingDate(
            @NonNull LocalDate date, @NonNull VendorBillPostingDateRule rule) {}

    /** One debit (positive) or credit (negative) of the entry, by {@code VENDOR_BILL} mapping key. */
    record Leg(@NonNull String mappingKey, @NonNull BigDecimal signedAmount) {}

    /** Where a vendor's unreconciled difference posts (AW47): the class, its expense key for EXPENSE. */
    public record Difference(
            @NonNull VendorBillDifferenceClass differenceClass,
            @Nullable String expenseMappingKey) {}

    /** The entry's legs, the rounding put on the largest debit, and the unreconciled difference posted. */
    record Entry(
            @NonNull List<Leg> legs,
            @NonNull BigDecimal roundingAdjustment,
            @Nullable Difference difference,
            @Nullable BigDecimal differenceAmount) {}

    /**
     * Posts {@code bill} for its approval. Joins the approval's transaction: any refusal propagates and rolls the
     * approval back with it (AW42).
     *
     * @param overrideJustification honoured by the period gate only for a holder of {@code accounting:period:override}
     * @return the posting row, entry and date included
     * @throws VendorBillException 409 {@code AP_BILL_NOT_APPROVABLE} when the bill is already posted or is held for
     *     its currency; 422 {@code AP_BILL_ZERO_TOTAL} for a bill of 0.00, {@code AP_BILL_UNCLASSIFIED} without a
     *     needed class and {@code AP_BILL_TOTALS_UNRECONCILED} without a needed difference; 400 {@code
     *     VALIDATION_ERROR} for a class the document cannot take
     * @throws GLMappingNotConfiguredException 422 for a key with no active mapping on the posting date
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
        // S32d item 10: the stated tax by type a recovery-enabled tenant recovers, decided before anything is written.
        VendorBillTaxSplit.Plan plan = taxSplit.plan(bill);
        Entry entry = entry(
                bill,
                billLines.findByVendorBill_VendorBillIdOrderByLineNumber(billId),
                effective,
                difference(bill),
                plan.recoveredByKey());

        PostingDate postingDate = postingDate(bill);
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        for (Leg leg : entry.legs()) {
            UUID account = resolve(bill, leg.mappingKey(), postingDate.date());
            lines.add(line(account, leg.signedAmount(), describe(leg, bill)));
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
        posting.setRoundingAdjustment(entry.roundingAdjustment());
        if (entry.difference() != null) {
            posting.setDifferenceClass(entry.difference().differenceClass());
            posting.setDifferenceAmount(entry.differenceAmount());
            posting.setDifferenceJustification(bill.getDifferenceJustification());
        }
        posting.setPostedAt(Instant.now(clock));
        posting.setPostedBy(actor);
        VendorBillGlPosting saved = postings.saveAndFlush(posting);
        recordRecovery(bill, saved, plan);
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
     * The decision on {@code bill}'s stated tax its posting would make now (S32d item 10), writing nothing: automatic
     * approval asks it before it posts, so a bill whose tax is not split or whose evidence is missing waits for a
     * person (AW51, AW53).
     *
     * @throws com.positivity.accounting.internal.exception.TaxServiceUnavailableException when pos-tax's profile or
     *     evidence rule cannot be read
     */
    public VendorBillTaxSplit.@NonNull Plan taxPlan(@NonNull VendorBill bill) {
        return taxSplit.plan(bill);
    }

    /**
     * The recovery {@code bill}'s posting recorded, for the approval's audit row (S32d item 10): {@code
     * inputTaxRecovery=<taxType>:<amount>-><key>|<taxType>:<amount>:<reason>}; null when it recorded none (a tenant
     * without recovery, or a bill without tax).
     */
    public @Nullable String recoveryAudit(@NonNull UUID billId) {
        List<VendorBillTaxRecovery> rows = taxRecoveries.findByVendorBillIdOrderByTaxTypeAsc(billId);
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("inputTaxRecovery=");
        for (int i = 0; i < rows.size(); i++) {
            VendorBillTaxRecovery row = rows.get(i);
            if (i > 0) {
                text.append('|');
            }
            text.append(row.getTaxType() == null ? "UNSPLIT" : row.getTaxType())
                    .append(':')
                    .append(row.getStatedAmount()
                            .setScale(SCALE, RoundingMode.HALF_UP)
                            .toPlainString());
            if (row.getRecoveryWithheldReason() == null) {
                text.append("->").append(row.getMappingKey());
            } else {
                text.append(':').append(row.getRecoveryWithheldReason());
            }
        }
        return text.toString();
    }

    /** One recovery row per stated amount, signed like the bill, for a recovery-enabled tenant (S32d item 10). */
    private void recordRecovery(VendorBill bill, VendorBillGlPosting posting, VendorBillTaxSplit.Plan plan) {
        if (!plan.enabled() || plan.items().isEmpty()) {
            return;
        }
        BigDecimal sign = gross(bill).signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        Instant now = Instant.now(clock);
        List<VendorBillTaxRecovery> rows = new ArrayList<>();
        for (VendorBillTaxSplit.Item item : plan.items()) {
            VendorBillTaxRecovery row = new VendorBillTaxRecovery();
            row.setVendorBillId(bill.getVendorBillId());
            row.setVendorBillGlPostingId(posting.getVendorBillGlPostingId());
            row.setTaxType(item.taxType());
            row.setRegime(item.regime());
            row.setStatedAmount(item.amount().multiply(sign));
            row.setRecoveredAmount(item.recovered() ? item.amount().multiply(sign) : BigDecimal.ZERO);
            row.setMappingKey(item.mappingKey());
            row.setRecoveryWithheldReason(
                    item.withheld() == null ? null : item.withheld().name());
            row.setCreatedAt(now);
            rows.add(row);
        }
        taxRecoveries.saveAll(rows);
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
        // Bound for the reversal reaction: this reversal is the bill's void, the one route that may reverse it.
        JournalEntryResponse reversal = VendorBillReversalReaction.underVoid(
                bill.getVendorBillId(),
                () -> journalEntryService.reverseJournalEntry(
                        posting.getJournalEntryId(),
                        truncate("Vendor bill " + bill.getBillNumber() + " voided", 200),
                        voidDate,
                        overrideJustification));
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

    /**
     * Refuses, writing nothing, what the content of {@code bill} would get at its posting (CAP:550 S13 guard step 5):
     * builds the entry's legs from the stored lines with {@code classification} and {@code difference}, so a bill
     * without a needed class is 422 {@code AP_BILL_UNCLASSIFIED} (and a class the document cannot take 400 {@code
     * VALIDATION_ERROR}) before the posting's period and mapping checks.
     */
    public void requirePostable(
            @NonNull VendorBill bill, @Nullable Classification classification, @Nullable Difference difference) {
        entry(
                bill,
                billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()),
                classification == null ? new Classification(null, null) : classification,
                difference);
    }

    /**
     * Resolves each leg's mapping on {@code date} as {@link #post} does, writing nothing: 422 {@code
     * GL_MAPPING_NOT_CONFIGURED} for the first key with no active mapping. Automatic approval asks this before it
     * posts (CAP:550 S13, #2510), in a transaction of its own, so a missing mapping never reaches the match's.
     */
    public void requireMapped(@NonNull VendorBill bill, @NonNull List<Leg> legs, @NonNull LocalDate date) {
        for (Leg leg : legs) {
            resolve(bill, leg.mappingKey(), date);
        }
    }

    /** The source event of a bill's entry, derived from its durable key {@code VENDOR_BILL:<billId>}. */
    public static @NonNull UUID sourceEventId(@NonNull UUID billId) {
        return UUID.nameUUIDFromBytes((SOURCE_KEY_PREFIX + billId).getBytes(StandardCharsets.UTF_8));
    }

    // ---- the entry ------------------------------------------------------------------------------------------

    /**
     * The entry's legs by mapping key, summed per key, the credit last. Debits are positive, credits negative; they
     * net to zero, the billed gross on {@code ACCOUNTS_PAYABLE}. A residual within the rounding tolerance goes to the
     * largest debit and is returned as the rounding adjustment (AW47).
     *
     * @param difference where an unreconciled difference of the vendor's totals posts; null when none was decided
     * @throws VendorBillException 422 {@code AP_BILL_ZERO_TOTAL} for a bill of 0.00; 422 {@code
     *     AP_BILL_TOTALS_UNRECONCILED} when the vendor's totals are apart by more than the tolerance and no
     *     difference is decided
     */
    static @NonNull Entry entry(
            @NonNull VendorBill bill,
            @NonNull List<VendorBillLine> lines,
            @NonNull Classification classification,
            @Nullable Difference difference) {
        return entry(bill, lines, classification, difference, Map.of());
    }

    /**
     * {@link #entry(VendorBill, List, Classification, Difference)} for a recovery-enabled tenant (CAP:550 S32d item
     * 10): each recovered amount debits its {@code TAX_RECOVERABLE_<regime>} key (a credit note credits it), and only
     * the tax not recovered goes into the class, prorated by line net on a bill with lines. Recoverable tax never
     * reaches 2100, 5050 or inventory cost.
     *
     * @param recovered the recovered amounts by mapping key, positive; empty books the gross as before
     */
    static @NonNull Entry entry(
            @NonNull VendorBill bill,
            @NonNull List<VendorBillLine> lines,
            @NonNull Classification classification,
            @Nullable Difference difference,
            @NonNull Map<String, BigDecimal> recovered) {
        BigDecimal gross = gross(bill);
        BigDecimal sign = gross.signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        BigDecimal recoveredTotal = recovered.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (gross.signum() == 0) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_ZERO_TOTAL,
                    "Bill " + bill.getBillNumber() + " totals 0.00; there is nothing to approve or post. Correct it"
                            + " or void it");
        }
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        Difference posted = null;
        BigDecimal differenceAmount = null;
        Optional<VendorBillTotals> totals = VendorBillTotals.of(bill);
        if (totals.isPresent() || lines.isEmpty() || gross.signum() < 0) {
            VendorBillTotals stated = totals.orElseGet(() -> legacyTotals(bill, gross));
            // The class keeps only the tax not recovered; the recovered tax has its own debit below.
            VendorBillTotals classTotals = new VendorBillTotals(
                    stated.gross(),
                    stated.net(),
                    stated.tax().subtract(recoveredTotal.multiply(sign)),
                    stated.difference(),
                    stated.tolerance());
            if (gross.signum() < 0) {
                creditNote(bill, classTotals.net().add(classTotals.tax()), classification, debits);
            } else {
                headerOnly(bill, classTotals, classification, debits);
            }
            recovered.forEach((key, amount) -> add(debits, key, amount.multiply(sign)));
            if (!stated.reconciled()) {
                if (difference == null) {
                    throw unreconciled(bill, stated);
                }
                add(debits, differenceKey(bill, difference), stated.difference());
                posted = difference;
                differenceAmount = stated.difference();
            }
        } else {
            byLine(
                    bill,
                    lines,
                    classification,
                    debits,
                    scaled(bill.getTaxAmount()).abs().subtract(recoveredTotal));
            recovered.forEach((key, amount) -> add(debits, key, amount.multiply(sign)));
            BigDecimal residual = gross.subtract(debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            if (residual.abs().compareTo(VendorBillTotals.TOLERANCE_PER_BILL) > 0) {
                // L-a: the lines' debits and the billed total are apart by more than rounding; never absorbed.
                throw new VendorBillException(
                        VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED,
                        "Bill " + bill.getBillNumber() + " totals " + gross.toPlainString() + " but its lines post "
                                + gross.subtract(residual).toPlainString() + " (difference " + residual.toPlainString()
                                + ", more than the " + VendorBillTotals.TOLERANCE_PER_BILL.toPlainString()
                                + " rounding allowed); correct the bill or void it");
            }
        }
        BigDecimal rounding = balanceOnLargest(debits, gross, recovered.keySet());
        List<Leg> legs = new ArrayList<>();
        debits.forEach((key, amount) -> {
            if (amount.signum() != 0) {
                legs.add(new Leg(key, amount));
            }
        });
        legs.add(new Leg(ACCOUNTS_PAYABLE_KEY, gross.negate()));
        return new Entry(legs, rounding, posted, differenceAmount);
    }

    /** The legs alone; see {@link #entry}. */
    static @NonNull List<Leg> legs(
            @NonNull VendorBill bill,
            @NonNull List<VendorBillLine> lines,
            @NonNull Classification classification,
            @Nullable Difference difference) {
        return entry(bill, lines, classification, difference).legs();
    }

    /** The legs alone, with the recovered tax; see {@link #entry(VendorBill, List, Classification, Difference, Map)}. */
    static @NonNull List<Leg> legs(
            @NonNull VendorBill bill,
            @NonNull List<VendorBillLine> lines,
            @NonNull Classification classification,
            @Nullable Difference difference,
            @NonNull Map<String, BigDecimal> recovered) {
        return entry(bill, lines, classification, difference, recovered).legs();
    }

    /**
     * Refuses a bill whose vendor totals need a decision nobody has made (AW47): 422 {@code
     * AP_BILL_TOTALS_UNRECONCILED}. The approval service asks before writing anything; the posting asks again.
     */
    static void requireReconciled(@NonNull VendorBill bill, @Nullable Difference difference) {
        Optional<VendorBillTotals> totals = VendorBillTotals.of(bill);
        if (totals.isPresent() && !totals.get().reconciled() && difference == null) {
            throw unreconciled(bill, totals.get());
        }
    }

    /** The difference decided on {@code bill} (at submission, approval or acceptance), or null. */
    static @Nullable Difference difference(@NonNull VendorBill bill) {
        return bill.getDifferenceClass() == null
                ? null
                : new Difference(bill.getDifferenceClass(), bill.getDifferenceExpenseMappingKey());
    }

    private static VendorBillException unreconciled(VendorBill bill, VendorBillTotals totals) {
        return new VendorBillException(
                VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED,
                totals.explanation() + " (difference " + totals.difference().toPlainString() + ") on bill "
                        + bill.getBillNumber() + "; say where it posts (difference: FREIGHT, GOODS, EXPENSE or"
                        + " PRICE_DIFFERENCE, with a justification), correct the bill or void it");
    }

    /** A bill stored before AW47 without its net: net = gross - tax, the tax as stated. */
    private static VendorBillTotals legacyTotals(VendorBill bill, BigDecimal gross) {
        BigDecimal tax = scaled(bill.getTaxAmount());
        if (gross.signum() < 0 && tax.signum() > 0) {
            tax = tax.negate();
        }
        return new VendorBillTotals(gross, gross.subtract(tax), tax, BigDecimal.ZERO.setScale(SCALE), BigDecimal.ZERO);
    }

    /** A credit note (AW39): Dr accounts payable / Cr its class, tax included. {@code signed} is negative. */
    private static void creditNote(
            VendorBill bill, BigDecimal signed, Classification classification, Map<String, BigDecimal> debits) {
        VendorBillDebitClass debitClass = requireClass(bill, classification);
        switch (debitClass) {
            case EXPENSE -> add(debits, expenseKey(bill, classification), signed);
            case PRICE_ALLOWANCE -> add(debits, PURCHASE_PRICE_DIFFERENCE_KEY, signed);
            default ->
                throw new VendorBillException(
                        VendorBillException.Code.VALIDATION_ERROR,
                        "A credit note posts as EXPENSE or PRICE_ALLOWANCE, not " + debitClass);
        }
    }

    /** A bill whose lines are not stored (AW39, AW47): one class for the whole bill, at the stated net and tax. */
    private static void headerOnly(
            VendorBill bill, VendorBillTotals stated, Classification classification, Map<String, BigDecimal> debits) {
        VendorBillDebitClass debitClass = requireClass(bill, classification);
        switch (debitClass) {
            case GOODS -> {
                add(debits, GOODS_RECEIVED_NOT_BILLED_KEY, stated.net());
                add(debits, PURCHASE_PRICE_DIFFERENCE_KEY, stated.tax());
            }
            case EXPENSE ->
                add(debits, expenseKey(bill, classification), stated.net().add(stated.tax()));
            default ->
                throw new VendorBillException(
                        VendorBillException.Code.VALIDATION_ERROR,
                        "A bill without receipt-matched lines posts as GOODS or EXPENSE, not " + debitClass);
        }
    }

    private static String differenceKey(VendorBill bill, Difference difference) {
        return switch (difference.differenceClass()) {
            case FREIGHT -> FREIGHT_IN_KEY;
            case GOODS -> GOODS_RECEIVED_NOT_BILLED_KEY;
            case PRICE_DIFFERENCE -> PURCHASE_PRICE_DIFFERENCE_KEY;
            case EXPENSE -> {
                String key = difference.expenseMappingKey();
                if (key == null || key.isBlank()) {
                    throw new VendorBillException(
                            VendorBillException.Code.VALIDATION_ERROR,
                            "difference.expenseMappingKey is required with class EXPENSE on bill "
                                    + bill.getBillNumber());
                }
                yield key.trim();
            }
        };
    }

    /** A goods-receipt bill (AW39): each line by its own class, a header tax prorated by line net. */
    private static void byLine(
            VendorBill bill,
            List<VendorBillLine> lines,
            Classification classification,
            Map<String, BigDecimal> debits,
            BigDecimal classTax) {
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
        List<BigDecimal> taxShares = prorate(classTax, nets);
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

    /**
     * Puts {@code gross - sum(debits)} on the largest debit and returns it, the rounding adjustment. A recovered tax
     * amount is copied as stated, so it never takes the rounding while another debit can.
     */
    private static BigDecimal balanceOnLargest(Map<String, BigDecimal> debits, BigDecimal gross, Set<String> stated) {
        BigDecimal sum = debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residual = gross.subtract(sum).setScale(SCALE, RoundingMode.HALF_UP);
        if (residual.signum() == 0 || debits.isEmpty()) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        boolean other = debits.keySet().stream().anyMatch(key -> !stated.contains(key));
        String largest = debits.entrySet().stream()
                .filter(e -> !other || !stated.contains(e.getKey()))
                .max(Comparator.comparing(e -> e.getValue().abs()))
                .map(Map.Entry::getKey)
                .orElseThrow();
        debits.merge(largest, residual, BigDecimal::add);
        return residual;
    }

    /**
     * The account of {@code key} on {@code date} (#2601): a missing mapping, one not effective on the date, or one
     * whose account is not active then, is one refusal, 422 {@code GL_MAPPING_NOT_CONFIGURED}, naming the category,
     * the key and the posting date, and what to do next.
     */
    private UUID resolve(VendorBill bill, String key, LocalDate date) {
        UUID account;
        try {
            account = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, key, date.atStartOfDay());
            glAccountService.validateAccountForPosting(account, date.atStartOfDay());
        } catch (GLMappingNotConfiguredException | GLAccountNotActiveException | GLAccountNotFoundException missing) {
            throw new GLMappingNotConfiguredException(
                    "No active " + POSTING_CATEGORY + " mapping for key " + key + " on " + date + "; bill "
                            + bill.getBillNumber() + " cannot post (" + missing.getMessage() + ")",
                    POSTING_CATEGORY,
                    key,
                    "Map " + POSTING_CATEGORY + " / " + key + " to an active account effective on " + date
                            + " in GL mappings, then approve the bill again");
        }
        return account;
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

    private static String describe(Leg leg, VendorBill bill) {
        String mappingKey = leg.mappingKey();
        String what =
                switch (mappingKey) {
                    case ACCOUNTS_PAYABLE_KEY ->
                        leg.signedAmount().signum() > 0 ? "Credit from vendor" : "Owed to vendor";
                    case GOODS_RECEIVED_NOT_BILLED_KEY -> "Goods received, now billed";
                    case PURCHASE_PRICE_DIFFERENCE_KEY -> "Purchase price difference and tax on goods";
                    case FREIGHT_IN_KEY -> "Freight on the vendor's bill";
                    default ->
                        mappingKey.startsWith(VendorBillTaxSplit.RECOVERABLE_KEY_PREFIX)
                                ? "Recoverable tax "
                                        + mappingKey.substring(VendorBillTaxSplit.RECOVERABLE_KEY_PREFIX.length())
                                : "Expense " + mappingKey.substring(Math.min(mappingKey.length(), 8));
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
