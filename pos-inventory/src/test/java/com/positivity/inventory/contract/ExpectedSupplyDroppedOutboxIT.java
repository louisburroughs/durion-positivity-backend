package com.positivity.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.OutboxEvent;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.repository.OutboxEventRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.service.PurchaseOrderService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * IT for odoo-parity A4 (issue #1028): cancelling a purchase order that still has open
 * (un-received) quantity writes an {@link ExpectedSupplyDroppedV1} fact to the transactional
 * outbox in the same transaction.
 *
 * <p>The Kafka flag stays off (no broker in tests); a real {@link OutboxEventWriter} is
 * registered directly so the genuine outbox write path runs against the H2 {@code event_outbox}
 * table.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PO cancel with open quantity drops expected supply into the outbox")
class ExpectedSupplyDroppedOutboxIT {

    @TestConfiguration
    static class OutboxTestConfig {
        @Bean
        OutboxEventWriter outboxEventWriter(
                Clock clock, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
            return new OutboxEventWriter(clock, objectMapper, outboxEventRepository);
        }
    }

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("cancel of a partially received PO writes one ExpectedSupplyDroppedV1 outbox row per SKU")
    void cancelWithOpenQuantityWritesDropFactToOutbox() {
        UUID skuId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        PurchaseOrderEntity po = seedPo(PurchaseOrderStatus.PARTIALLY_RECEIVED, siteId, skuId, new BigDecimal("6"));

        purchaseOrderService.cancelPurchaseOrder(po.getPurchaseOrderId(), "outbox-it");

        List<OutboxEvent> rows = outboxEventRepository.findAll();
        List<OutboxEvent> dropRows = rows.stream()
                .filter(row -> row.getPayload().contains(ExpectedSupplyDroppedV1.EVENT_TYPE))
                .toList();
        assertThat(dropRows).hasSize(1);
        String payload = dropRows.getFirst().getPayload();
        assertThat(payload).contains(skuId.toString());
        assertThat(payload).contains(siteId.toString());
        assertThat(payload).contains(po.getPurchaseOrderId().toString());
        assertThat(payload).contains("CANCELLED");
        assertThat(dropRows.getFirst().getRecordKey()).isNotBlank();
    }

    @Test
    @DisplayName("cancel of a DRAFT PO (never counted as supply) writes no drop fact")
    void cancelOfDraftPoWritesNoDropFact() {
        PurchaseOrderEntity po =
                seedPo(PurchaseOrderStatus.DRAFT, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("6"));

        purchaseOrderService.cancelPurchaseOrder(po.getPurchaseOrderId(), "outbox-it");

        assertThat(outboxEventRepository.findAll())
                .noneMatch(row -> row.getPayload().contains(ExpectedSupplyDroppedV1.EVENT_TYPE));
    }

    private PurchaseOrderEntity seedPo(PurchaseOrderStatus status, UUID siteId, UUID skuId, BigDecimal openQuantity) {
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .vendorId(UUID.randomUUID())
                .poNumber("DRP-" + UUID.randomUUID())
                .status(status)
                .currency("USD")
                .subtotalMinor(100L)
                .taxMinor(0L)
                .grandTotalMinor(100L)
                .shipToLocationId(siteId)
                .build();
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineNumber(1)
                .skuId(skuId)
                .description("outbox drop line")
                .quantityDecimal(openQuantity)
                .unitCostMinor(100L)
                .lineTotalMinor(100L)
                .openQuantityDecimal(openQuantity)
                .build();
        line.setPurchaseOrder(po);
        po.getLines().add(line);
        return purchaseOrderRepository.save(po);
    }
}
