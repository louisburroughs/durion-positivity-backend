package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.OrderCancelReviewRequiredV1;
import com.positivity.domainevents.order.OrderCancelledV1;
import com.positivity.domainevents.order.OrderCommissionImpactV1;
import com.positivity.domainevents.order.OrderCompletedV1;
import com.positivity.domainevents.order.OrderPaymentIntegrityAlertV1;
import com.positivity.domainevents.order.OrderReturnedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.order.internal.entity.PriceOverride;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.RefundMethod;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.ReturnCondition;
import com.positivity.order.internal.entity.ReturnOrder;
import com.positivity.order.internal.entity.ReturnOrderLine;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SourceType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link OrderDomainEventPublisher} (ADR-0044, stories D1/D2/D3).
 *
 * <p>
 * These facts are what pos-inventory, pos-accounting, and the CRM act on, so the
 * details that decide downstream behaviour are the ones worth pinning:
 *
 * <ul>
 * <li><b>A WORKORDER-sourced line is not fulfillable</b> (spec R7.5). The source
 * document already fulfilled it; marking it fulfillable would have pos-inventory
 * consume the stock a second time.</li>
 * <li><b>The aggregate id is the aggregate the fact is about</b> — the return for
 * {@code order.order.returned}, the session for {@code order.session.closed} —
 * not the order in every case, because the aggregate id is the Kafka key and
 * therefore what orders the stream.</li>
 * <li><b>A persisted completion timestamp wins over the clock</b>, so the fact
 * matches the aggregate state rather than the moment it happened to be
 * published.</li>
 * <li><b>Every method is a no-op while the Kafka flag is off.</b> The writer bean
 * is conditional, and these are called inside mutating transactions that must
 * still commit.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderDomainEventPublisher — order domain facts")
class OrderDomainEventPublisherTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LINE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID RETURN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID OVERRIDE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final String TOPIC = DomainTopics.events("order");

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxProvider;

    @Mock
    private OutboxEventWriter outboxWriter;

    private OrderDomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OrderDomainEventPublisher(Clock.fixed(NOW, ZoneOffset.UTC), outboxProvider);
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);
    }

    private DomainEventEnvelope<?> captureEnvelope() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
        verify(outboxWriter).publish(org.mockito.ArgumentMatchers.eq(TOPIC), captor.capture());
        return captor.getValue();
    }

    private static SalesOrder order(Long version, SalesOrderLine... lines) {
        return SalesOrder.builder()
                .orderId(ORDER_ID)
                .version(version)
                .orderNumber("SO-1001")
                .locationId(UUID.randomUUID())
                .sessionId(SESSION_ID)
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .subtotal(new BigDecimal("100.00"))
                .discountTotal(new BigDecimal("5.00"))
                .taxTotal(new BigDecimal("8.25"))
                .grandTotal(new BigDecimal("103.25"))
                .amountPaid(new BigDecimal("120.00"))
                .cancellationReason("Customer changed mind")
                .lines(new ArrayList<>(Arrays.asList(lines)))
                .build();
    }

    private static SalesOrderLine line(SourceType sourceType, PriceSource priceSource, List<String> serials) {
        return SalesOrderLine.builder()
                .orderLineId(LINE_ID)
                .itemSku("BRK-100")
                .itemDescription("Brake pad")
                .quantity(2)
                .unitPrice(new BigDecimal("45.50"))
                .discountAmount(new BigDecimal("1.00"))
                .lineSubtotal(new BigDecimal("90.00"))
                .taxAmount(new BigDecimal("7.42"))
                .lineTotal(new BigDecimal("97.42"))
                .priceSource(priceSource)
                .sourceType(sourceType)
                .sourceId("wo-1")
                .sourceLineId("wol-1")
                .returnable(true)
                .serialNumbers(serials)
                .build();
    }

    @Nested
    @DisplayName("when the Kafka flag is off")
    class KafkaDisabled {

        @Test
        @DisplayName("every publish is a no-op so the mutating transaction still commits")
        void allMethodsNoOp() {
            when(outboxProvider.getIfAvailable()).thenReturn(null);
            SalesOrder order = order(1L);

            publisher.publishOrderCancelled(order);
            publisher.publishCancelReviewRequired(order, "inventory unavailable");
            publisher.publishOrderCompleted(order, List.of());
            publisher.publishPaymentIntegrityAlert(order);
            publisher.publishCommissionImpact(override(null, null));
            publisher.publishOrderReturned(returnOrder(null, null));
            publisher.publishRegisterSessionClosed(session(null), sessionClosedPayload());

            verifyNoInteractions(outboxWriter);
        }
    }

    @Nested
    @DisplayName("order.order.completed")
    class Completed {

        @Test
        @DisplayName("marks a WORKORDER-sourced line unfulfillable so inventory does not double-consume it")
        void workorderSourcedLineIsNotFulfillable() {
            publisher.publishOrderCompleted(
                    order(4L, line(SourceType.WORKORDER, PriceSource.MANUAL, List.of("SN-1"))),
                    List.of(new OrderCompletedV1.Tender("CARD", new BigDecimal("103.25"))));

            OrderCompletedV1 payload = (OrderCompletedV1) captureEnvelope().payload();
            OrderCompletedV1.Line eventLine = payload.lines().getFirst();
            assertThat(eventLine.fulfillable()).isFalse();
            assertThat(eventLine.sku()).isEqualTo("BRK-100");
            assertThat(eventLine.priceSource()).isEqualTo("MANUAL");
            assertThat(eventLine.sourceType()).isEqualTo("WORKORDER");
            assertThat(eventLine.serialNumbers()).containsExactly("SN-1");
            assertThat(payload.tenders()).hasSize(1);
            assertThat(payload.currencyCode()).isEqualTo("USD");
            assertThat(payload.completedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("marks a line with no source document fulfillable")
        void ordinaryLineIsFulfillable() {
            publisher.publishOrderCompleted(order(4L, line(null, null, null)), List.of());

            OrderCompletedV1.Line eventLine =
                    ((OrderCompletedV1) captureEnvelope().payload()).lines().getFirst();
            assertThat(eventLine.fulfillable()).isTrue();
            assertThat(eventLine.sourceType()).isNull();
            // Defaults rather than nulls: the field is @NonNull on the event contract.
            assertThat(eventLine.priceSource()).isEqualTo("PRICING_SERVICE");
            assertThat(eventLine.serialNumbers()).isEmpty();
        }

        @Test
        @DisplayName("skips a null line rather than failing the whole fact")
        void nullLinesAreSkipped() {
            SalesOrder order = order(4L, line(null, PriceSource.PRICING_SERVICE, List.of()));
            order.getLines().add(null);

            publisher.publishOrderCompleted(order, List.of());

            assertThat(((OrderCompletedV1) captureEnvelope().payload()).lines()).hasSize(1);
        }

        @Test
        @DisplayName("keys the fact on the order and carries its optimistic-lock version")
        void keyedOnOrderWithVersion() {
            publisher.publishOrderCompleted(order(7L), List.of());

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(OrderCompletedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(ORDER_ID);
            assertThat(envelope.aggregateVersion()).isEqualTo(7L);
            assertThat(envelope.sourceService()).isEqualTo("pos-order");
            assertThat(envelope.actor()).isEqualTo("system");
        }

        @Test
        @DisplayName("treats an unversioned order as version zero rather than failing envelope validation")
        void nullVersionBecomesZero() {
            publisher.publishOrderCompleted(order(null), List.of());

            assertThat(captureEnvelope().aggregateVersion()).isZero();
        }
    }

    @Nested
    @DisplayName("cancellation facts")
    class Cancellation {

        @Test
        @DisplayName("emits order.order.cancelled with the recorded reason")
        void cancelled() {
            publisher.publishOrderCancelled(order(2L));

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(OrderCancelledV1.EVENT_TYPE);
            OrderCancelledV1 payload = (OrderCancelledV1) envelope.payload();
            assertThat(payload.orderId()).isEqualTo(ORDER_ID);
            assertThat(payload.orderNumber()).isEqualTo("SO-1001");
            assertThat(payload.cancellationReason()).isEqualTo("Customer changed mind");
        }

        @Test
        @DisplayName("emits order.order.cancel-review-required carrying why the cancel could not complete")
        void cancelReviewRequired() {
            publisher.publishCancelReviewRequired(order(2L), "inventory release failed");

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(OrderCancelReviewRequiredV1.EVENT_TYPE);
            assertThat(((OrderCancelReviewRequiredV1) envelope.payload()).failureReason())
                    .isEqualTo("inventory release failed");
        }
    }

    @Nested
    @DisplayName("order.payment.integrity-alert")
    class IntegrityAlert {

        @Test
        @DisplayName("carries both figures that disagree, so the discrepancy is in the fact itself")
        void carriesBothAmounts() {
            publisher.publishPaymentIntegrityAlert(order(1L));

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(OrderPaymentIntegrityAlertV1.EVENT_TYPE);
            OrderPaymentIntegrityAlertV1 payload = (OrderPaymentIntegrityAlertV1) envelope.payload();
            assertThat(payload.grandTotal()).isEqualByComparingTo("103.25");
            assertThat(payload.amountPaid()).isEqualByComparingTo("120.00");
        }
    }

    @Nested
    @DisplayName("order.line.commission-impact")
    class CommissionImpact {

        @Test
        @DisplayName("reports the discount delta and the approval timestamp the override actually carries")
        void usesApprovedAtAndComputesDelta() {
            Instant approvedAt = NOW.minusSeconds(600);

            publisher.publishCommissionImpact(override(PriceOverrideReasonCode.PRICE_MATCH, approvedAt));

            OrderCommissionImpactV1 payload =
                    (OrderCommissionImpactV1) captureEnvelope().payload();
            assertThat(payload.overrideId()).isEqualTo(OVERRIDE_ID);
            assertThat(payload.orderLineId()).isEqualTo(LINE_ID);
            assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
            assertThat(payload.affectsCommission()).isTrue();
            assertThat(payload.discountDelta()).isEqualByComparingTo("15.00");
            assertThat(payload.reasonCode()).isEqualTo("PRICE_MATCH");
            assertThat(payload.effectiveAt()).isEqualTo(approvedAt);
        }

        @Test
        @DisplayName("falls back to UNSPECIFIED and the current clock when the override records neither")
        void defaultsReasonCodeAndTimestamp() {
            publisher.publishCommissionImpact(override(null, null));

            OrderCommissionImpactV1 payload =
                    (OrderCommissionImpactV1) captureEnvelope().payload();
            assertThat(payload.reasonCode()).isEqualTo("UNSPECIFIED");
            assertThat(payload.effectiveAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("order.order.returned")
    class Returned {

        @Test
        @DisplayName("keys the fact on the return, not the original order")
        void keyedOnTheReturn() {
            publisher.publishOrderReturned(returnOrder(3L, NOW.minusSeconds(60)));

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(OrderReturnedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(RETURN_ID).isNotEqualTo(ORDER_ID);
            assertThat(envelope.aggregateVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("carries the persisted completion time so the fact matches the aggregate state")
        void usesPersistedReturnedAt() {
            Instant returnedAt = NOW.minusSeconds(3_600);

            publisher.publishOrderReturned(returnOrder(1L, returnedAt));

            OrderReturnedV1 payload = (OrderReturnedV1) captureEnvelope().payload();
            assertThat(payload.returnedAt()).isEqualTo(returnedAt);
            assertThat(payload.refundMethod()).isEqualTo("ORIGINAL_TENDER");
            assertThat(payload.lines()).singleElement().satisfies(l -> {
                assertThat(l.condition()).isEqualTo(ReturnCondition.RESTOCK.name());
                assertThat(l.quantity()).isEqualTo(1);
                assertThat(l.serialNumbers()).containsExactly("SN-9");
            });
        }

        @Test
        @DisplayName("falls back to the clock and an empty serial list when neither is recorded")
        void defaultsWhenUnrecorded() {
            ReturnOrder returnOrder = returnOrder(null, null);
            returnOrder.getLines().getFirst().setSerialNumbers(null);

            publisher.publishOrderReturned(returnOrder);

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.aggregateVersion()).isZero();
            OrderReturnedV1 payload = (OrderReturnedV1) envelope.payload();
            assertThat(payload.returnedAt()).isEqualTo(NOW);
            assertThat(payload.lines().getFirst().serialNumbers()).isEmpty();
        }

        @Test
        @DisplayName("skips a null line rather than failing the whole fact")
        void nullLineIsSkipped() {
            ReturnOrder returnOrder = returnOrder(1L, NOW);
            returnOrder.getLines().add(null);

            publisher.publishOrderReturned(returnOrder);

            assertThat(((OrderReturnedV1) captureEnvelope().payload()).lines()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("order.session.closed")
    class SessionClosed {

        @Test
        @DisplayName("keys the fact on the session and forwards the caller's payload unchanged")
        void keyedOnSession() {
            RegisterSessionClosedV1 payload = sessionClosedPayload();

            publisher.publishRegisterSessionClosed(session(5L), payload);

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(RegisterSessionClosedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(SESSION_ID);
            assertThat(envelope.aggregateVersion()).isEqualTo(5L);
            assertThat(envelope.payload()).isSameAs(payload);
        }

        @Test
        @DisplayName("treats an unversioned session as version zero")
        void nullVersionBecomesZero() {
            publisher.publishRegisterSessionClosed(session(null), sessionClosedPayload());

            assertThat(captureEnvelope().aggregateVersion()).isZero();
        }

        @Test
        @DisplayName("does not publish when the writer bean is absent")
        void noWriterNoPublish() {
            when(outboxProvider.getIfAvailable()).thenReturn(null);

            publisher.publishRegisterSessionClosed(session(1L), sessionClosedPayload());

            verify(outboxWriter, never()).publish(anyString(), any());
        }
    }

    private static PriceOverride override(PriceOverrideReasonCode reasonCode, Instant approvedAt) {
        return PriceOverride.builder()
                .overrideId(OVERRIDE_ID)
                .order(order(1L))
                .orderLine(SalesOrderLine.builder().orderLineId(LINE_ID).build())
                .productId(PRODUCT_ID)
                .originalPrice(new BigDecimal("60.00"))
                .overridePrice(new BigDecimal("45.00"))
                .reasonCode(reasonCode)
                .affectsCommission(true)
                .requestedByUserId(UUID.randomUUID())
                .approvedByUserId(UUID.randomUUID())
                .approvedAt(approvedAt)
                .build();
    }

    private static ReturnOrder returnOrder(Long version, Instant returnedAt) {
        ReturnOrderLine line = ReturnOrderLine.builder()
                .returnLineId(UUID.randomUUID())
                .originalOrderLineId(LINE_ID)
                .itemSku("BRK-100")
                .returnQty(1)
                .condition(ReturnCondition.RESTOCK)
                .lineRefund(new BigDecimal("45.50"))
                .serialNumbers(new ArrayList<>(List.of("SN-9")))
                .build();
        return ReturnOrder.builder()
                .returnOrderId(RETURN_ID)
                .version(version)
                .originalOrderId(ORDER_ID)
                .originalOrderNumber("SO-1001")
                .locationId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .refundMethod(RefundMethod.ORIGINAL_TENDER)
                .totalRefund(new BigDecimal("45.50"))
                .returnedAt(returnedAt)
                .lines(new ArrayList<>(List.of(line)))
                .build();
    }

    private static RegisterSession session(Long version) {
        return RegisterSession.builder()
                .sessionId(SESSION_ID)
                .version(version)
                .terminalId("terminal-1")
                .locationId(UUID.randomUUID())
                .openedByClerkId("clerk-1")
                .openingFloat(new BigDecimal("200.00"))
                .build();
    }

    private static RegisterSessionClosedV1 sessionClosedPayload() {
        return new RegisterSessionClosedV1(
                SESSION_ID,
                "terminal-1",
                UUID.randomUUID(),
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("450.00"),
                new BigDecimal("452.00"),
                new BigDecimal("-2.00"),
                false,
                "USD",
                List.of(),
                BigDecimal.ZERO,
                NOW.minusSeconds(28_800),
                NOW);
    }
}
