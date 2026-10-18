package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ExtInventoryGoodsReceipt;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtInventoryGoodsReceiptRepository;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.tenancy.TenantIterator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Fills the goods-receipt copy from the facts already stored (#2750; ruling 6 on the issue). Receipts recorded before
 * {@code ext_inventory_goods_receipt} existed have an ingestion record that keeps the {@code goodsreceipt.recorded}
 * payload but no copy row. At each start, per tenant, this replays those payloads through {@link GoodsReceiptReplica},
 * the copy's only writer, so ADR-0044 R3 holds: the copy is written only from the owner's facts, by the fact
 * consumer's own writer. A SQL backfill in the migration cannot do this under FORCE row-level security.
 *
 * <p>Stored facts are replayed oldest first, so a corrected re-emit is applied after the fact it corrects and the
 * first one's {@code occurredAt} stays the received date. A receipt that already has a copy row when the run starts is
 * left alone: the live consumer has written it from a fact at least as new as anything stored. A fact the accrual
 * rules call malformed is never copied, as on the live path, and an unreadable stored payload is skipped with a
 * warning. Each slice commits in a transaction of its own, so a failure loses at most that slice; the next start
 * resumes, because only receipts without a row are written. One tenant's failure is logged and the next tenant still
 * runs; nothing here stops the service from starting.
 *
 * <p>Turn it off with {@code pos.accounting.goods-receipt-replica.backfill-enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "pos.accounting.goods-receipt-replica.backfill-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GoodsReceiptReplicaBackfill implements ApplicationRunner {

    static final int SLICE_SIZE = 200;

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

    @Override
    public void run(ApplicationArguments args) {
        try {
            backfill();
        } catch (Exception e) {
            log.error("Goods receipt copy backfill failed; continuing startup", e);
        }
    }

    /**
     * Backfills every registry tenant.
     *
     * @return the number of receipts copied across all tenants
     */
    int backfill() {
        int[] copied = {0};
        tenantIterator.forEachActiveTenant(tenantId -> {
            try {
                copied[0] += backfillTenant();
            } catch (Exception e) {
                log.error("Goods receipt copy backfill failed for tenant {}; continuing", tenantId, e);
            }
        });
        log.info("Goods receipt copy backfill: {} receipt(s) copied", copied[0]);
        return copied[0];
    }

    /** Backfills the bound tenant; returns the receipts copied. */
    public int backfillTenant() {
        Set<UUID> existing = new HashSet<>(receipts.findAll().stream()
                .map(ExtInventoryGoodsReceipt::getGoodsReceiptId)
                .toList());
        Set<UUID> written = new HashSet<>();
        int page = 0;
        boolean more = true;
        while (more) {
            PageRequest request = PageRequest.of(page++, SLICE_SIZE);
            Boolean hasNext = transaction.execute(_ -> {
                Slice<AccountingEvent> slice = events.findByEventTypeOrderByReceivedAtAscEventIdAsc(
                        GoodsReceiptRecordedV1.EVENT_TYPE, request);
                for (AccountingEvent event : slice) {
                    copy(event, existing, written);
                }
                return slice.hasNext();
            });
            more = Boolean.TRUE.equals(hasNext);
        }
        return written.size();
    }

    private void copy(AccountingEvent event, Set<UUID> existing, Set<UUID> written) {
        GoodsReceiptRecordedV1 fact;
        try {
            fact = objectMapper.convertValue(event.getPayload(), GoodsReceiptRecordedV1.class);
        } catch (Exception e) {
            log.warn(
                    "Skipping unreadable stored goods receipt fact eventId={}: {}", event.getEventId(), e.getMessage());
            return;
        }
        if (fact == null || existing.contains(fact.receiptId())) {
            return;
        }
        if (assessor.assess(fact) instanceof GoodsReceiptAccrualPostingService.Assessment.Malformed) {
            return;
        }
        replica.recorded(fact);
        written.add(fact.receiptId());
    }
}
