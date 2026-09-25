package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts the journal entry for a consumed {@code inventory.adjustment.posted} fact — a cycle-count
 * variance or a manual adjustment (issue #2191, spec SPEC-inventory-adjustment-gl-posting §4.4–§4.6).
 *
 * <p>Amount is {@code unitCost x abs(quantityDelta)}, sign-routed through the
 * {@code INVENTORY_ADJUSTMENT} posting category (the {@code REGISTER_OVER_SHORT} shape):
 *
 * <ul>
 *   <li>loss ({@code quantityDelta < 0}): {@code Dr ADJUSTMENT_LOSS / Cr INVENTORY_ASSET};
 *   <li>gain ({@code quantityDelta > 0}): {@code Dr INVENTORY_ASSET / Cr ADJUSTMENT_GAIN}.
 * </ul>
 *
 * <p>Seeded as 5100 Inventory Shrinkage / 1300 Inventory for both loss and gain (#2186 D2: count
 * over/short nets in one account). Accounts are never hardcoded, and {@code reasonCode} rides into
 * the description only — no reason-code routing (D9).
 *
 * <p>Idempotency mirrors {@link InventoryShrinkagePostingService}: the posting key
 * {@code INVENTORY_ADJUSTMENT_GL_POSTING:<adjustmentKind>:<adjustmentId>} is registered in the same
 * transaction as the journal entry, so a fact re-emitted under a fresh {@code eventId} posts nothing
 * more. The kind is in the key because cycle-count and manual adjustment ids are separate id
 * spaces. The journal entry's {@code sourceEventId} is derived from the same pair, and an existing
 * entry with it is also treated as already posted, because posting keys expire.
 *
 * <p>The transaction date is the fact's {@code occurredAt} (business time), so redeliveries land in
 * the same period and resolve the same effective-dated mapping. The period gate inside
 * {@link JournalEntryService#postJournalEntry} applies; a closed or hard-locked period, like a
 * missing mapping, propagates to the listener for container retry and DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryAdjustmentPostingService {

    static final String POSTING_CATEGORY_NAME = "INVENTORY_ADJUSTMENT";
    static final String LOSS_MAPPING_KEY = "ADJUSTMENT_LOSS";
    static final String GAIN_MAPPING_KEY = "ADJUSTMENT_GAIN";
    static final String INVENTORY_ASSET_MAPPING_KEY = "INVENTORY_ASSET";
    static final String IDEMPOTENCY_KEY_PREFIX = "INVENTORY_ADJUSTMENT_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "INVENTORY_ADJUSTMENT:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final JournalEntryRepository journalEntryRepository;

    /**
     * Post the balanced adjustment journal entry, exactly once per adjustment kind and id.
     *
     * <p>The caller must already have routed away an uncosted fact ({@code unitCost} null or
     * non-positive); this method assumes a costed adjustment.
     *
     * @param fact the consumed adjustment fact (with a non-null, positive {@code unitCost})
     * @return the posted journal entry's id, or {@code null} when the adjustment was already posted
     */
    @Transactional
    public @Nullable UUID postAdjustment(@NonNull InventoryAdjustedV1 fact) {
        String idempotencyKey = idempotencyKey(fact.adjustmentKind(), fact.adjustmentId());
        UUID sourceEventId = toSourceEventId(fact.adjustmentKind(), fact.adjustmentId());
        // The posting key expires (IdempotencyService, 24 h); the journal entry's deterministic
        // sourceEventId is the durable backstop for a re-emit that arrives after it has.
        if (idempotencyService.isKeyProcessed(idempotencyKey)
                || !journalEntryRepository.findBySourceEvent(sourceEventId).isEmpty()) {
            log.info(
                    "Inventory adjustment GL posting already processed, skipping | kind={} | adjustmentId={}",
                    fact.adjustmentKind(),
                    fact.adjustmentId());
            return null;
        }

        BigDecimal unitCost = fact.unitCost();
        if (unitCost == null || unitCost.signum() <= 0) {
            // Internal invariant: the listener routes uncosted facts to the skip path first.
            throw new IllegalArgumentException("Unpostable adjustment fact reached posting (unitCost=" + unitCost
                    + "): " + fact.adjustmentKind() + ":" + fact.adjustmentId());
        }
        BigDecimal amount = unitCost.multiply(fact.quantityDelta().abs());

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(fact.occurredAt(), clock.getZone());

        boolean loss = fact.quantityDelta().signum() < 0;
        UUID debitAccountId;
        UUID creditAccountId;
        if (loss) {
            // Dr Adjustment Loss (shrinkage) / Cr Inventory
            debitAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, LOSS_MAPPING_KEY, transactionDate);
            creditAccountId = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY_NAME, INVENTORY_ASSET_MAPPING_KEY, transactionDate);
        } else {
            // Dr Inventory / Cr Adjustment Gain
            debitAccountId = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY_NAME, INVENTORY_ASSET_MAPPING_KEY, transactionDate);
            creditAccountId =
                    glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, GAIN_MAPPING_KEY, transactionDate);
        }

        String description = "Inventory adjustment " + (loss ? "loss" : "gain") + " " + fact.adjustmentKind() + " "
                + fact.adjustmentId() + " (reason " + fact.reasonCode() + ", sku " + fact.sku() + ", delta "
                + fact.quantityDelta().toPlainString() + " @ " + unitCost.toPlainString() + ", ledger entry "
                + fact.ledgerEntryId() + ")";

        UUID posted = glPostingService.postInventoryAdjustment(
                sourceEventId,
                fact.adjustmentId(),
                debitAccountId,
                creditAccountId,
                amount,
                transactionDate,
                description,
                null);

        idempotencyService.registerKey(idempotencyKey, posted);

        log.info(
                "Inventory adjustment GL posting completed | kind={} | adjustmentId={} | sku={} | reasonCode={} "
                        + "| direction={} | amount={} | journalEntryId={}",
                fact.adjustmentKind(),
                fact.adjustmentId(),
                fact.sku(),
                fact.reasonCode(),
                loss ? "LOSS" : "GAIN",
                amount,
                posted);
        return posted;
    }

    static @NonNull String idempotencyKey(@NonNull String adjustmentKind, @NonNull UUID adjustmentId) {
        return IDEMPOTENCY_KEY_PREFIX + adjustmentKind + ":" + adjustmentId;
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the adjustment kind and
     * id, namespaced so it never collides with another entry deriving from the same id.
     */
    static @NonNull UUID toSourceEventId(@NonNull String adjustmentKind, @NonNull UUID adjustmentId) {
        return UUID.nameUUIDFromBytes(
                (SOURCE_EVENT_NAMESPACE + adjustmentKind + ":" + adjustmentId).getBytes(StandardCharsets.UTF_8));
    }
}
