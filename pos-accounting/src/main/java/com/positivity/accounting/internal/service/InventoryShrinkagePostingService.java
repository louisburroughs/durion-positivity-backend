package com.positivity.accounting.internal.service;

import com.positivity.domainevents.inventory.ScrapPostedV1;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts the shrinkage journal entry for a consumed {@code inventory.scrap.posted} fact
 * (odoo-parity D2, issue #1043): {@code Dr Inventory Shrinkage (expense) / Cr Inventory (asset)}
 * for {@code quantity x unitCost}.
 *
 * <p>Accounts are never hardcoded: both sides resolve through the {@code INVENTORY_SHRINKAGE}
 * posting category and its {@code SHRINKAGE_EXPENSE} / {@code INVENTORY_ASSET} mapping keys
 * (seeded by {@code R__seed_reference_accounting.sql} as 5100 Inventory Shrinkage and
 * 1300 Inventory).
 *
 * <p>Idempotency (mirrors {@code CustomerCreditIssuanceGLPostingEventHandler}): the posting key is
 * the scrap id, namespaced with {@code INVENTORY_SHRINKAGE_GL_POSTING:}, registered in the same
 * transaction as the posted journal entry — so a shrinkage posts exactly once per scrapId even if
 * the fact is redelivered under a fresh Kafka {@code eventId} (which would slip past the
 * {@code processed_events} guard in {@link InventoryEventsListener}).
 *
 * <p>The journal entry's transaction date is the fact's {@code occurredAt} (business time), never
 * processing/clock time, so redeliveries post into the same accounting period and resolve the same
 * effective-dated GL mapping. The Wave 2 period gate applies inside
 * {@link com.positivity.accounting.internal.service.JournalEntryService#postJournalEntry}; a CLOSED or
 * hard-locked period propagates unwrapped to the listener for container retry / DLQ.
 *
 * <p>The scrap {@code reasonCode} rides into the entry description verbatim — unknown reason codes
 * never block posting (spec D4 scope 4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryShrinkagePostingService {

    static final String POSTING_CATEGORY_NAME = "INVENTORY_SHRINKAGE";
    static final String DEBIT_MAPPING_KEY = "SHRINKAGE_EXPENSE";
    static final String CREDIT_MAPPING_KEY = "INVENTORY_ASSET";
    static final String IDEMPOTENCY_KEY_PREFIX = "INVENTORY_SHRINKAGE_GL_POSTING:";
    static final String SOURCE_EVENT_NAMESPACE = "INVENTORY_SHRINKAGE:";

    private final Clock clock;
    private final IdempotencyService idempotencyService;
    private final GLMappingResolver glMappingResolver;
    private final GLPostingService glPostingService;

    /**
     * Post the balanced shrinkage journal entry for a scrap fact, exactly once per scrapId.
     *
     * <p>The caller must have already routed away unpostable facts ({@code unitCost} null or
     * non-positive) — this method assumes a costed scrap.
     *
     * @param fact the consumed scrap fact (with a non-null, positive {@code unitCost})
     */
    @Transactional
    public void postShrinkage(@NonNull ScrapPostedV1 fact) {
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + fact.scrapId();
        if (idempotencyService.isKeyProcessed(idempotencyKey)) {
            log.info("Inventory shrinkage GL posting already processed, skipping | scrapId={}", fact.scrapId());
            return;
        }

        BigDecimal unitCost = fact.unitCost();
        if (unitCost == null || unitCost.signum() <= 0) {
            // (d) Defensive/internal invariant: this is a Kafka-driven consumer (ScrapPostedV1),
            // not reachable from a controller. The class javadoc documents that the caller must
            // already have routed away unpostable facts, so this guard should never fire; if it
            // does, it is a server-side routing defect, correctly answered as a correlated 500
            // by the pos-web-common catch-all now that no blanket handler maps it to 400.
            throw new IllegalArgumentException(
                    "Unpostable scrap fact reached posting (unitCost=" + unitCost + "): " + fact.scrapId());
        }
        BigDecimal amount = unitCost.multiply(BigDecimal.valueOf(fact.quantity()));

        // Business time, not processing time: redeliveries land in the same period.
        LocalDateTime transactionDate = LocalDateTime.ofInstant(fact.occurredAt(), clock.getZone());

        // Account resolution via posting category / mapping key configuration — never hardcoded.
        UUID shrinkageAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, DEBIT_MAPPING_KEY, transactionDate);
        UUID inventoryAccountId =
                glMappingResolver.resolveGLAccount(POSTING_CATEGORY_NAME, CREDIT_MAPPING_KEY, transactionDate);

        String description = "Inventory shrinkage for scrap " + fact.scrapId() + " (reason " + fact.reasonCode()
                + ", sku " + fact.sku() + ", qty " + fact.quantity() + " @ " + unitCost + ")"
                + (fact.workorderId() != null ? " [workorder " + fact.workorderId() + "]" : "");

        UUID posted = glPostingService.postInventoryShrinkage(
                toSourceEventId(fact.scrapId()),
                fact.scrapId(),
                shrinkageAccountId,
                inventoryAccountId,
                amount,
                transactionDate,
                description,
                null);

        idempotencyService.registerKey(idempotencyKey, posted);

        log.info(
                "Inventory shrinkage GL posting completed | scrapId={} | sku={} | reasonCode={} | amount={} "
                        + "| journalEntryId={}",
                fact.scrapId(),
                fact.sku(),
                fact.reasonCode(),
                amount,
                posted);
    }

    /**
     * Derive the journal entry {@code sourceEventId} deterministically from the scrap id,
     * namespaced so it never collides with another entry deriving from the same id. Replays of
     * the same scrap always produce the same source event id.
     */
    static @NonNull UUID toSourceEventId(@NonNull UUID scrapId) {
        return UUID.nameUUIDFromBytes((SOURCE_EVENT_NAMESPACE + scrapId).getBytes(StandardCharsets.UTF_8));
    }
}
