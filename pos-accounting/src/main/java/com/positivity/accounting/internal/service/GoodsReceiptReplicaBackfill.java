package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtInventoryGoodsReceiptRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.tenancy.TenantIterator;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Fills the goods-receipt copy from the facts already stored (#2750; ruling 6 on the issue). Receipts recorded before
 * {@code ext_inventory_goods_receipt} existed have an ingestion record that keeps the {@code goodsreceipt.recorded}
 * payload but no copy row. Once the application is ready, per tenant, this replays those payloads through {@link
 * GoodsReceiptReplica}, the copy's only writer, so ADR-0044 R3 holds: the copy is written only from the owner's facts,
 * by the fact consumer's own writer. A SQL backfill in the migration cannot do this under FORCE row-level security.
 *
 * <p>It runs on a thread of its own after startup, so a large history never delays the service, and it never fails
 * startup. The read is one keyset query (oldest first, {@code (received_at, event_id)}) of the stored facts whose
 * receipt has no copy row, so a start with nothing to fill costs one empty query per tenant. Each slice commits in a
 * transaction of its own; a failure loses at most that slice and the next start resumes, because only receipts without
 * a row are read.
 *
 * <p>Per receipt, the first stored fact found inserts the row and the receipt's whole stored history is then applied
 * oldest first in the same transaction, so a corrected re-emit lands after the fact it corrects and the first fact's
 * {@code occurredAt} stays the received date. A row that exists when the receipt is reached and was not created by this
 * run belongs to the live consumer, which has written it from a fact at least as new as anything stored: it is left
 * alone. A fact the accrual rules call malformed is never copied, as on the live path, and an unreadable stored payload
 * is skipped with a warning (a receipt with nothing copyable is read again, and skipped again, at each start).
 *
 * <p>Turn it off with {@code pos.accounting.goods-receipt-replica.backfill-enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "pos.accounting.goods-receipt-replica.backfill-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GoodsReceiptReplicaBackfill {

    static final int SLICE_SIZE = 200;
    private static final UUID NIL = new UUID(0L, 0L);

    private final TenantIterator tenantIterator;
    private final AccountingEventRepository events;
    private final ExtInventoryGoodsReceiptRepository receipts;
    private final GoodsReceiptReplica replica;
    private final GoodsReceiptAccrualPostingService assessor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public GoodsReceiptReplicaBackfill(
            TenantIterator tenantIterator,
            AccountingEventRepository events,
            ExtInventoryGoodsReceiptRepository receipts,
            GoodsReceiptReplica replica,
            GoodsReceiptAccrualPostingService assessor,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.tenantIterator = tenantIterator;
        this.events = events;
        this.receipts = receipts;
        this.replica = replica;
        this.assessor = assessor;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Starts the backfill on a daemon thread once the application is ready; never fails startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofPlatform().daemon().name("goods-receipt-replica-backfill").start(this::runSafely);
    }

    /** The backfill as the startup thread runs it: any failure is logged, none escapes. */
    void runSafely() {
        try {
            backfill();
        } catch (Exception e) {
            log.error("Goods receipt copy backfill failed; the next start resumes it", e);
        }
    }

    /**
     * Backfills every registry tenant.
     *
     * @return the number of receipts copied across all tenants
     */
    int backfill() {
        int[] copied = {0};
        TenantIterator.Sweep sweep = tenantIterator.sweep(tenantId -> {
            try {
                copied[0] += backfillTenant();
            } catch (Exception e) {
                log.error("Goods receipt copy backfill failed for tenant {}; continuing", tenantId, e);
            }
        });
        if (!sweep.completeTenantList()) {
            log.warn("Goods receipt copy backfill ran over an incomplete tenant list; the next start covers the rest");
        }
        log.info("Goods receipt copy backfill: {} receipt(s) copied", copied[0]);
        return copied[0];
    }

    /** Backfills the bound tenant; returns the receipts copied. */
    public int backfillTenant() {
        Set<UUID> written = new HashSet<>();
        Instant afterReceivedAt = Instant.EPOCH;
        UUID afterEventId = NIL;
        while (true) {
            Instant cursorReceivedAt = afterReceivedAt;
            UUID cursorEventId = afterEventId;
            List<AccountingEvent> slice = transaction.execute(_ -> {
                List<AccountingEvent> page =
                        events.findGoodsReceiptFactsWithoutCopy(cursorReceivedAt, cursorEventId, SLICE_SIZE);
                for (AccountingEvent event : page) {
                    copy(event, written);
                }
                return page;
            });
            if (slice == null || slice.size() < SLICE_SIZE) {
                return written.size();
            }
            AccountingEvent last = slice.get(slice.size() - 1);
            afterReceivedAt = last.getReceivedAt();
            afterEventId = last.getEventId();
        }
    }

    private void copy(AccountingEvent event, Set<UUID> written) {
        GoodsReceiptRecordedV1 first = read(event);
        if (first == null || written.contains(first.receiptId())) {
            return;
        }
        // A row this run did not create is the live consumer's: skip it, whatever the stored facts say.
        if (receipts.existsById(first.receiptId())) {
            return;
        }
        List<AccountingEvent> history = events.findByEventTypeAndDomainKeyIdOrderByReceivedAtAscEventIdAsc(
                GoodsReceiptRecordedV1.EVENT_TYPE, first.receiptId().toString());
        boolean copied = false;
        for (AccountingEvent stored : history.isEmpty() ? List.of(event) : history) {
            GoodsReceiptRecordedV1 fact = stored == event ? first : read(stored);
            if (fact == null
                    || assessor.assess(fact) instanceof GoodsReceiptAccrualPostingService.Assessment.Malformed) {
                continue;
            }
            replica.recorded(fact);
            copied = true;
        }
        if (copied) {
            written.add(first.receiptId());
        }
    }

    private GoodsReceiptRecordedV1 read(AccountingEvent event) {
        try {
            return objectMapper.convertValue(event.getPayload(), GoodsReceiptRecordedV1.class);
        } catch (Exception e) {
            log.warn(
                    "Skipping unreadable stored goods receipt fact eventId={}: {}", event.getEventId(), e.getMessage());
            return null;
        }
    }
}
