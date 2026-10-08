package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a goods receipt's own accrual from pos-inventory's {@code goodsreceipt.recorded} fact (CAP:550 S41, #2602;
 * AW38 as amended on #2598): the day a delivery is received into stock, its value goes on the books, owed as
 * "delivered, not yet billed".
 *
 * <p><b>The entry.</b> One journal entry per receipt, dated the fact's {@code occurredAt} in the tenant's accounting
 * calendar, every account through the {@value #POSTING_CATEGORY} posting category ({@link GLMappingResolver}, never by
 * number). Per costed receipt line:
 *
 * <ul>
 *   <li>Dr {@code INVENTORY_ASSET} (1300) at the line's {@code inventoryValueMinor}, as Inventory states it;
 *   <li>Cr {@code GOODS_RECEIVED_NOT_BILLED} (2100) at its {@code accruedAmountMinor};
 *   <li>Dr or Cr {@code PURCHASE_PRICE_DIFFERENCE} (5050) for the difference: the {@code STANDARD} variance and
 *       pack-price rounding, or, for an unpriced line (accrual 0, value above 0), Cr 5050 at its value, on a journal
 *       line of its own whose description names the line as unpriced.
 * </ul>
 *
 * Over the receipt that is Dr 1300 Σ value / Cr 2100 Σ accrual / ± 5050 the difference. Every journal line names its
 * receipt line, purchase-order line or sku, {@code costSource} and {@code ledgerEntryId}, for tie-out only:
 * Accounting never branches on them. A vendor bill never debits 1300 (ADR-0048 §1, §3); its approval clears 2100
 * ({@link VendorBillPostingService}).
 *
 * <p><b>Accounting never substitutes a value Inventory did not state</b> ({@link #assess}, checked by the listener
 * before posting):
 *
 * <ol>
 *   <li>currency first: a fact with no {@code currencyCode}, or one other than the ledger's, is never booked at par
 *       (ADR-0067 PC-9 (a), PC-13 (a)): held {@code SUSPENDED / CURRENCY_NOT_SUPPORTED};
 *   <li>malformed, the whole fact held {@code SUSPENDED / VALIDATION_ERROR}, never a partial posting: a received line
 *       without {@code receiptLineId}, a null value beside a non-zero accrual, an amount on a line with no quantity,
 *       a negative quantity, or line accruals that do not sum to {@code totalAccruedAmountMinor}. A receipt only
 *       ever adds stock: a return or a correction arrives as {@code vendorreturn.recorded}, never as a negative
 *       receipt line, so Accounting never books one (#2602 review, ruling 1 of #2602);
 *   <li>a line with no value and no accrual is uncosted and contributes nothing; when every received line is, the
 *       fact is {@code SKIPPED / UNCOSTED_FACT}, terminal like an uncosted scrap or adjustment;
 *   <li>nothing accrued and nothing valued posts nothing ({@code PROCESSED}); lines with zero quantity and no amount
 *       are ignored, while the sum check still counts them.
 * </ol>
 *
 * <p><b>Amounts</b> convert from minor units by the currency's exponent only; Accounting never rounds a receipt amount
 * (ADR-0067 PC-5 (a)).
 *
 * <p><b>Idempotency.</b> The posting key {@code GOODS_RECEIPT_ACCRUAL:<receiptId>} is registered with the entry, whose
 * {@code sourceEventId} derives from the same key and is the durable backstop once the key expires: a fact re-emitted
 * under a new {@code eventId} posts nothing. A closed or hard-locked period, a missing mapping or a transient failure
 * propagates to the listener for container retry and the DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoodsReceiptAccrualPostingService {

    /** The posting category every receipt account resolves through. */
    public static final String POSTING_CATEGORY = "GOODS_RECEIPT";

    static final String INVENTORY_ASSET_KEY = "INVENTORY_ASSET";
    static final String GOODS_RECEIVED_NOT_BILLED_KEY = "GOODS_RECEIVED_NOT_BILLED";
    static final String PURCHASE_PRICE_DIFFERENCE_KEY = "PURCHASE_PRICE_DIFFERENCE";

    /** Prefix of a receipt's posting key, and the namespace of its entry's source event. */
    public static final String POSTING_KEY_PREFIX = "GOODS_RECEIPT_ACCRUAL:";

    private static final int DESCRIPTION_MAX = 500;

    private final AccountingCalendarZoneResolver zoneResolver;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerCurrency ledgerCurrency;

    /** What a receipt fact calls for, decided from the fact alone, before any transaction. */
    public sealed interface Assessment {

        /** No currency, or not the ledger's: held {@code SUSPENDED / CURRENCY_NOT_SUPPORTED}. */
        record CurrencyNotSupported(@NonNull String detail) implements Assessment {}

        /** The whole fact is held {@code SUSPENDED / VALIDATION_ERROR}. */
        record Malformed(@NonNull String detail) implements Assessment {}

        /** Every received line is uncosted: {@code SKIPPED / UNCOSTED_FACT}. */
        record Uncosted(@NonNull String detail) implements Assessment {}

        /** Nothing accrued and nothing valued: {@code PROCESSED}, no entry. */
        record NothingToPost() implements Assessment {}

        /** The entry's legs, debits positive, credits negative. */
        record Postable(@NonNull List<Leg> legs) implements Assessment {}
    }

    /** One journal line by mapping key: a debit when {@code signedAmount} is positive, a credit when negative. */
    public record Leg(
            @NonNull String mappingKey,
            @NonNull BigDecimal signedAmount,
            @NonNull String description) {}

    /** Decides what {@code fact} calls for: the currency first, then the line rules; see the class doc. */
    public @NonNull Assessment assess(@NonNull GoodsReceiptRecordedV1 fact) {
        String currency = fact.currencyCode();
        if (currency == null || currency.isBlank()) {
            return new Assessment.CurrencyNotSupported("Goods receipt " + receiptLabel(fact)
                    + " states no currency; its amounts are never booked at par in " + ledgerCurrency.code()
                    + " (ADR-0067 PC-9)");
        }
        if (ledgerCurrency.isForeign(currency)) {
            return new Assessment.CurrencyNotSupported("Goods receipt " + receiptLabel(fact) + " is in "
                    + currency.trim() + ", not the ledger currency " + ledgerCurrency.code()
                    + "; it is never booked at par (ADR-0067 PC-9)");
        }

        long accruedSum = 0L;
        List<String> problems = new ArrayList<>();
        List<GoodsReceiptLine> received = new ArrayList<>();
        for (GoodsReceiptLine line : fact.lines()) {
            accruedSum += line.accruedAmountMinor();
            if (line.quantityReceived().signum() == 0) {
                // Ignored only when it carries no amount: an amount with no quantity would otherwise be dropped
                // without a trace, so it holds the fact like any other inconsistency.
                long value = line.inventoryValueMinor() == null ? 0L : line.inventoryValueMinor();
                if (line.accruedAmountMinor() != 0L || value != 0L) {
                    problems.add("an accrual of " + line.accruedAmountMinor() + " and a value of " + value
                            + " on a line with no quantity (" + lineLabel(line) + ")");
                }
                continue;
            }
            if (line.quantityReceived().signum() < 0) {
                problems.add("a negative quantity " + line.quantityReceived().toPlainString() + " (" + lineLabel(line)
                        + "); a return or correction arrives as vendorreturn.recorded");
            }
            received.add(line);
            if (line.receiptLineId() == null) {
                problems.add("a line without receiptLineId (" + lineLabel(line) + ")");
            }
            if (line.inventoryValueMinor() == null && line.accruedAmountMinor() != 0L) {
                problems.add("no inventoryValueMinor beside an accrual of " + line.accruedAmountMinor() + " ("
                        + lineLabel(line) + ")");
            }
        }
        if (accruedSum != fact.totalAccruedAmountMinor()) {
            problems.add("line accruals sum to " + accruedSum + ", not totalAccruedAmountMinor "
                    + fact.totalAccruedAmountMinor());
        }
        if (!problems.isEmpty()) {
            return new Assessment.Malformed(truncate(
                    "Goods receipt " + receiptLabel(fact) + " is malformed, nothing posted: "
                            + String.join("; ", problems),
                    DESCRIPTION_MAX));
        }

        List<GoodsReceiptLine> costed = received.stream()
                .filter(line -> line.inventoryValueMinor() != null)
                .toList();
        if (costed.isEmpty()) {
            if (received.isEmpty()) {
                return new Assessment.NothingToPost();
            }
            return new Assessment.Uncosted(truncate(
                    "Uncosted goods receipt " + receiptLabel(fact) + ", no accrual posted: every received line has"
                            + " no inventory value (" + received.size() + " line(s), costSource "
                            + received.stream()
                                    .map(line -> String.valueOf(line.costSource()))
                                    .distinct()
                                    .toList()
                            + ")",
                    DESCRIPTION_MAX));
        }
        if (costed.stream().allMatch(line -> line.inventoryValueMinor() == 0L && line.accruedAmountMinor() == 0L)) {
            return new Assessment.NothingToPost();
        }
        return new Assessment.Postable(legs(fact, costed, exponent(currency)));
    }

    /**
     * Post the receipt's accrual, exactly once per {@code receiptId}. The caller has assessed the fact {@link
     * Assessment.Postable}.
     *
     * @return the posted journal entry's id, or {@code null} when the receipt was already posted
     */
    @Transactional
    public @Nullable UUID postAccrual(@NonNull GoodsReceiptRecordedV1 fact) {
        String postingKey = postingKey(fact.receiptId());
        UUID sourceEventId = toSourceEventId(fact.receiptId());
        // The posting key expires (IdempotencyService, 24 h); the entry's deterministic sourceEventId is the durable
        // backstop for a re-emit that arrives after it has.
        if (idempotencyService.isKeyProcessed(postingKey)
                || !journalEntryRepository.findBySourceEvent(sourceEventId).isEmpty()) {
            log.info("Goods receipt accrual already posted, skipping | receiptId={}", fact.receiptId());
            return null;
        }
        if (!(assess(fact) instanceof Assessment.Postable postable)) {
            // Internal invariant: the listener routes every other assessment away first.
            throw new IllegalStateException("Unpostable goods receipt reached posting: " + fact.receiptId());
        }

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = zoneResolver.postingDateTime(fact.occurredAt());
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        for (Leg leg : postable.legs()) {
            UUID account = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, leg.mappingKey(), transactionDate);
            lines.add(line(account, leg.signedAmount(), leg.description()));
        }
        JournalEntryResponse created = journalEntryService.createJournalEntry(JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .sourceEventId(sourceEventId)
                .sourceEventType(JournalEntrySourceTypes.GOODS_RECEIPT_ACCRUAL)
                .description(truncate("Goods received " + receiptLabel(fact) + ", not yet billed", DESCRIPTION_MAX))
                .lines(lines)
                .build());
        UUID posted = journalEntryService
                .postJournalEntry(created.getJournalEntryId(), null)
                .getJournalEntryId();
        idempotencyService.registerKey(postingKey, posted);

        log.info(
                "Goods receipt accrual posted | receiptId={} | purchaseOrderId={} | lines={} | journalEntryId={}",
                fact.receiptId(),
                fact.purchaseOrderId(),
                lines.size(),
                posted);
        return posted;
    }

    /** A receipt's posting key, {@code GOODS_RECEIPT_ACCRUAL:<receiptId>}. */
    public static @NonNull String postingKey(@NonNull UUID receiptId) {
        return POSTING_KEY_PREFIX + receiptId;
    }

    /** The source event of a receipt's entry, derived from its posting key. */
    public static @NonNull UUID toSourceEventId(@NonNull UUID receiptId) {
        return UUID.nameUUIDFromBytes(postingKey(receiptId).getBytes(StandardCharsets.UTF_8));
    }

    // ---- the entry ----------------------------------------------------------------------------------------------

    /** Per costed line: Dr 1300 value / Cr 2100 accrual / ± 5050 the difference, zero legs left out. */
    private static List<Leg> legs(GoodsReceiptRecordedV1 fact, List<GoodsReceiptLine> costed, int exponent) {
        List<Leg> legs = new ArrayList<>();
        for (GoodsReceiptLine line : costed) {
            BigDecimal value = major(Objects.requireNonNull(line.inventoryValueMinor()), exponent);
            BigDecimal accrued = major(line.accruedAmountMinor(), exponent);
            String trace = lineLabel(line) + ", costSource " + line.costSource() + ", ledger entry "
                    + line.ledgerEntryId() + ", receipt " + receiptLabel(fact);
            add(legs, INVENTORY_ASSET_KEY, value, "Received into stock, qty " + qty(line) + " - " + trace);
            add(legs, GOODS_RECEIVED_NOT_BILLED_KEY, accrued.negate(), "Received, not yet billed - " + trace);
            boolean unpriced = line.accruedAmountMinor() == 0L && line.inventoryValueMinor() > 0L;
            add(
                    legs,
                    PURCHASE_PRICE_DIFFERENCE_KEY,
                    accrued.subtract(value),
                    unpriced
                            ? "Unpriced receipt line, valued pending the vendor's bill - " + trace
                            : "Purchase price difference (accrued " + accrued.toPlainString() + ", inventory value "
                                    + value.toPlainString() + ") - " + trace);
        }
        return legs;
    }

    private static void add(List<Leg> legs, String key, BigDecimal signed, String description) {
        if (signed.signum() != 0) {
            legs.add(new Leg(key, signed, truncate(description, DESCRIPTION_MAX)));
        }
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest line(
            UUID account, BigDecimal signed, String description) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(account)
                .debitAmount(signed.signum() > 0 ? signed : BigDecimal.ZERO)
                .creditAmount(signed.signum() < 0 ? signed.negate() : BigDecimal.ZERO)
                .description(description)
                .build();
    }

    /** Minor units to major by the exponent alone: exact, never rounded (ADR-0067 PC-5 (a)). */
    private static BigDecimal major(long minor, int exponent) {
        return BigDecimal.valueOf(minor, exponent);
    }

    private static int exponent(String currency) {
        int digits =
                Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        return Math.max(digits, 0);
    }

    private static String qty(GoodsReceiptLine line) {
        return line.quantityReceived().stripTrailingZeros().toPlainString();
    }

    private static String receiptLabel(GoodsReceiptRecordedV1 fact) {
        return (fact.receiptNumber() == null ? "" : fact.receiptNumber() + " ") + "(" + fact.receiptId() + ", PO "
                + fact.purchaseOrderId() + ")";
    }

    private static String lineLabel(GoodsReceiptLine line) {
        return "receipt line " + line.receiptLineId()
                + (line.poLineId() != null ? ", PO line " + line.poLineId() : ", sku " + line.sku());
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
