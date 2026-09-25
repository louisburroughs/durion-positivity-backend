package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
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
 * Posts the journal entry for a consumed {@code inventory.product-value.changed} fact — a manual
 * cost revaluation (issue #2193, spec SPEC-inventory-adjustment-gl-posting §4.10, #2186 decision
 * D7).
 *
 * <p>Amount is {@code abs(totalValueDelta)}, taken as delivered — inventory has already multiplied
 * the cost delta by the on-hand quantity it costed at revaluation time, so accounting never
 * recomputes it. Sign-routed through the {@code INVENTORY_REVALUATION} posting category:
 *
 * <ul>
 *   <li>write-up ({@code totalValueDelta > 0}): {@code Dr INVENTORY_ASSET / Cr REVALUATION_OFFSET};
 *   <li>write-down ({@code totalValueDelta < 0}): {@code Dr REVALUATION_OFFSET / Cr INVENTORY_ASSET}.
 * </ul>
 *
 * <p>Seeded as 1300 Inventory / 5000 Cost of Goods Sold (#2186 D7, final: the revaluation counter
 * account is 5000 COGS). Accounts are never hardcoded.
 *
 * <p>Idempotency mirrors {@link InventoryAdjustmentPostingService}: the posting key
 * {@code INVENTORY_REVALUATION_GL_POSTING:<revaluationId>} is registered in the same transaction as
 * the journal entry, so a fact re-emitted under a fresh {@code eventId} posts nothing more. The
 * journal entry's {@code sourceEventId} is derived from the same id, and an existing entry with it
 * is also treated as already posted, because posting keys expire.
 *
 * <p>There is no uncosted case: {@link ProductValueChangedV1#totalValueDelta()} is non-null and
 * always computed by inventory. A zero delta simply has nothing to post; the caller records it as
 * processed without a journal entry.
 *
 * <p>The transaction date is the fact's {@code occurredAt} (business time), so redeliveries land in
 * the same period and resolve the same effective-dated mapping. The period gate inside
 * {@link JournalEntryService#postJournalEntry} applies; a closed or hard-locked period, like a
 * missing mapping, propagates to the listener for container retry and DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryRevaluationPostingService {

    static final String POSTING_CATEGORY_NAME = "INVENTORY_REVALUATION";
    static final String INVENTORY_ASSET_MAPPING_KEY = "INVENTORY_ASSET";
    static final String REVALUATION_OFFSET_MAPPING_KEY = "REVALUATION_OFFSET";
    static final String IDEMPOTENCY_KEY_PREFIX = "INVENTORY_REVALUATION_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "INVENTORY_REVALUATION:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;
    private final JournalEntryRepository journalEntryRepository;

    /**
     * Post the balanced revaluation journal entry, exactly once per revaluation id. A zero
     * {@code totalValueDelta} posts nothing and returns {@code null}.
     *
     * @param fact the consumed revaluation fact
     * @return the posted journal entry's id, or {@code null} when the revaluation was already
     *     posted or its value delta is zero
     */
    @Transactional
    public @Nullable UUID postRevaluation(@NonNull ProductValueChangedV1 fact) {
        String idempotencyKey = idempotencyKey(fact.revaluationId());
        UUID sourceEventId = toSourceEventId(fact.revaluationId());
        // The posting key expires (IdempotencyService, 24 h); the journal entry's deterministic
        // sourceEventId is the durable backstop for a re-emit that arrives after it has.
        if (idempotencyService.isKeyProcessed(idempotencyKey)
                || !journalEntryRepository.findBySourceEvent(sourceEventId).isEmpty()) {
            log.info(
                    "Inventory revaluation GL posting already processed, skipping | revaluationId={}",
                    fact.revaluationId());
            return null;
        }

        BigDecimal totalValueDelta = fact.totalValueDelta();
        if (totalValueDelta.signum() == 0) {
            log.info(
                    "Inventory revaluation has zero value delta, nothing to post | revaluationId={}",
                    fact.revaluationId());
            return null;
        }
        BigDecimal amount = totalValueDelta.abs();

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(fact.occurredAt(), clock.getZone());

        boolean writeUp = totalValueDelta.signum() > 0;
        UUID debitAccountId;
        UUID creditAccountId;
        if (writeUp) {
            // Dr Inventory / Cr Revaluation Offset
            debitAccountId = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY_NAME, INVENTORY_ASSET_MAPPING_KEY, transactionDate);
            creditAccountId = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY_NAME, REVALUATION_OFFSET_MAPPING_KEY, transactionDate);
        } else {
            // Dr Revaluation Offset / Cr Inventory
            debitAccountId = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY_NAME, REVALUATION_OFFSET_MAPPING_KEY, transactionDate);
            creditAccountId = glMappingResolver.resolveGLAccount(
                    POSTING_CATEGORY_NAME, INVENTORY_ASSET_MAPPING_KEY, transactionDate);
        }

        String description = "Inventory revaluation " + (writeUp ? "write-up" : "write-down") + " "
                + fact.revaluationId() + " (sku " + fact.sku() + ", " + fact.costingMethod() + " cost "
                + (fact.previousUnitCost() == null
                        ? "null"
                        : fact.previousUnitCost().toPlainString()) + " -> "
                + fact.newUnitCost().toPlainString() + ", on-hand "
                + fact.onHandQuantity().toPlainString()
                + ", delta " + totalValueDelta.toPlainString() + ", reason " + fact.reason() + ", actor "
                + fact.actor() + ")";

        UUID posted = glPostingService.postInventoryRevaluation(
                sourceEventId,
                fact.revaluationId(),
                debitAccountId,
                creditAccountId,
                amount,
                transactionDate,
                description,
                null);

        idempotencyService.registerKey(idempotencyKey, posted);

        log.info(
                "Inventory revaluation GL posting completed | revaluationId={} | sku={} | direction={} | amount={} "
                        + "| journalEntryId={}",
                fact.revaluationId(),
                fact.sku(),
                writeUp ? "WRITE_UP" : "WRITE_DOWN",
                amount,
                posted);
        return posted;
    }

    static @NonNull String idempotencyKey(@NonNull UUID revaluationId) {
        return IDEMPOTENCY_KEY_PREFIX + revaluationId;
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the revaluation id,
     * namespaced so it never collides with another entry deriving from the same id.
     */
    static @NonNull UUID toSourceEventId(@NonNull UUID revaluationId) {
        return UUID.nameUUIDFromBytes((SOURCE_EVENT_NAMESPACE + revaluationId).getBytes(StandardCharsets.UTF_8));
    }
}
