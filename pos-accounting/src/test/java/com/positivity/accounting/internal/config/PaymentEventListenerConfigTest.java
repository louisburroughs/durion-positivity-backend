package com.positivity.accounting.internal.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PaymentClearedEvent;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.service.PaymentApplicationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class PaymentEventListenerConfigTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentApplicationService paymentApplicationService = Mockito.mock(PaymentApplicationService.class);

    private PaymentEventListenerConfig listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventListenerConfig(objectMapper, paymentApplicationService);
    }

    @Nested
    @DisplayName("onPaymentCleared()")
    class OnPaymentCleared {

        @Test
        @DisplayName("Should process valid PaymentCleared Kafka message")
        void processesValidPaymentClearedMessage() throws Exception {
            UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Instant clearedAt = Instant.now(TEST_CLOCK);
            BigDecimal amount = new BigDecimal("150.00");
            String currencyCode = "USD";

            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReceivablePayment());

            PaymentClearedEvent event = PaymentClearedEvent.builder()
                    .eventId(eventId)
                    .paymentId(paymentId)
                    .customerId(customerId)
                    .organizationId(organizationId)
                    .amount(amount)
                    .currencyCode(currencyCode)
                    .paymentMethod("CREDIT_CARD")
                    .clearedAt(clearedAt)
                    .build();

            String message = objectMapper.writeValueAsString(event);

            listener.onPaymentCleared(message);

            verify(paymentApplicationService)
                    .handlePaymentCleared(paymentId, customerId, currencyCode, amount, clearedAt, eventId);
        }

        @Test
        @DisplayName("Should skip invalid event with missing required fields")
        void skipsInvalidEventMissingFields() throws Exception {
            // Event missing paymentId and amount — should fail validation
            PaymentClearedEvent event = PaymentClearedEvent.builder()
                    .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .customerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .currencyCode("USD")
                    .paymentMethod("CHECK")
                    .build();

            String message = objectMapper.writeValueAsString(event);

            // Should not throw — invalid events are caught and skipped
            listener.onPaymentCleared(message);

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should skip malformed JSON message")
        void skipsMalformedJson() {
            String malformedMessage = "{ this is not valid json }}}";

            // Should not throw — malformed messages are caught and skipped
            listener.onPaymentCleared(malformedMessage);

            verify(paymentApplicationService, never()).handlePaymentCleared(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should rethrow service exceptions for Kafka retry")
        void rethrowsServiceExceptions() throws Exception {
            UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

            when(paymentApplicationService.handlePaymentCleared(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB connection failed"));

            PaymentClearedEvent event = PaymentClearedEvent.builder()
                    .eventId(eventId)
                    .paymentId(paymentId)
                    .customerId(customerId)
                    .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .amount(new BigDecimal("50.00"))
                    .currencyCode("USD")
                    .paymentMethod("ACH")
                    .clearedAt(Instant.now(TEST_CLOCK))
                    .build();

            String message = objectMapper.writeValueAsString(event);

            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class, () -> listener.onPaymentCleared(message));

            verify(paymentApplicationService)
                    .handlePaymentCleared(eq(paymentId), eq(customerId), any(), any(), any(), eq(eventId));
        }
    }
}
