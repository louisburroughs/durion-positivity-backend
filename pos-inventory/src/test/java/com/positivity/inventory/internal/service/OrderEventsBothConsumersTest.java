package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderReceiptRepository;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OrderEventsListener} and {@link PurchaseOrderProjectionListener} both consume
 * {@code order.events.v1}, and each order event has to reach both of them (#2176).
 *
 * <p>Two things stopped that. The listeners shared a consumer group, so Kafka split the partitions
 * between them and each record went to only one; and they shared a {@code processed_events} key, so
 * whichever marked an event first made the other skip it. The first is checked on the resolved
 * {@code @KafkaListener} group ids, the second against the real ledger table, which a mocked
 * repository cannot show.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("order.events.v1 reaches both inventory consumers (#2176)")
class OrderEventsBothConsumersTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtPurchaseOrderRepository orderRepository;

    @Autowired
    private ExtPurchaseOrderLineRepository lineRepository;

    @Autowired
    private ExtPurchaseOrderReceiptRepository receiptRepository;

    @Autowired
    private InventoryFactPublisher inventoryFactPublisher;

    @Autowired
    private CounterSaleIssuePoster counterSaleIssuePoster;

    private OrderEventsListener orderEventsListener;
    private PurchaseOrderProjectionListener projectionListener;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        ObjectMapper objectMapper = new ObjectMapper();
        orderEventsListener = new OrderEventsListener(
                Clock.systemUTC(),
                objectMapper,
                processedEventRepository,
                counterSaleIssuePoster,
                context.getBeanProvider(OutboxEventWriter.class),
                transactionManager);
        projectionListener = new PurchaseOrderProjectionListener(
                Clock.systemUTC(),
                objectMapper,
                processedEventRepository,
                orderRepository,
                lineRepository,
                receiptRepository,
                inventoryFactPublisher,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the two listeners are in different consumer groups")
    void listenersHaveTheirOwnConsumerGroups() throws NoSuchMethodException {
        String orderGroup = resolvedGroupId(OrderEventsListener.class.getMethod("onOrderEvent", String.class));
        String projectionGroup =
                resolvedGroupId(PurchaseOrderProjectionListener.class.getMethod("onOrderEvent", String.class));

        assertThat(projectionGroup)
                .as("two listeners in one group split the partitions, so each record reaches only one")
                .isNotEqualTo(orderGroup);
    }

    @Test
    @DisplayName("an event one listener has recorded is still applied by the other")
    void eventRecordedByOneListenerIsStillAppliedByTheOther() {
        String eventId = UUID.randomUUID().toString();
        UUID purchaseOrderId = UUID.randomUUID();
        String message = purchaseOrderUpdated(eventId, purchaseOrderId);

        // The order the defect needed: the counter-sale listener marks the event first (it ignores
        // this type, but still records it), then the projection sees it.
        orderEventsListener.onOrderEvent(message);
        projectionListener.onOrderEvent(message);

        assertThat(orderRepository.findById(purchaseOrderId))
                .as("the projection must apply an event the counter-sale listener already recorded")
                .isPresent();
        assertThat(processedEventRepository.existsByEventIdAndOwner(eventId, OrderEventsListener.OWNER))
                .isTrue();
        assertThat(processedEventRepository.existsByEventIdAndOwner(eventId, PurchaseOrderProjectionListener.OWNER))
                .isTrue();
    }

    private String resolvedGroupId(Method method) {
        KafkaListener listener = method.getAnnotation(KafkaListener.class);
        return environment.resolveRequiredPlaceholders(listener.groupId());
    }

    private static String purchaseOrderUpdated(String eventId, UUID purchaseOrderId) {
        return """
            {
              "eventId": "%s",
              "eventType": "purchaseorder.updated",
              "aggregateId": "%s",
              "aggregateVersion": 1,
              "payload": {
                "purchaseOrderId": "%s",
                "poNumber": "PO-2176",
                "vendorId": "%s",
                "status": "APPROVED",
                "shipToLocationId": "%s",
                "expectedDeliveryDate": "2026-10-01",
                "currency": "USD",
                "grandTotalMinor": 1000,
                "openBalanceMinor": 1000,
                "occurredAt": "2026-09-23T12:00:00Z",
                "lines": [
                  {
                    "lineId": "%s",
                    "lineNumber": 1,
                    "skuId": "%s",
                    "orderedQuantity": 2,
                    "openQuantity": 2,
                    "unitCostMinor": 500,
                    "description": "Oil filter"
                  }
                ]
              }
            }
            """.formatted(
                        eventId,
                        purchaseOrderId,
                        purchaseOrderId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
    }
}
