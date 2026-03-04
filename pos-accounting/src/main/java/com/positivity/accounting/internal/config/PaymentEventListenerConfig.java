package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.dto.PaymentClearedEvent;
import com.positivity.accounting.service.PaymentApplicationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka listener for PaymentCleared events (CAP:051 Story #114).
 * Consumes PaymentCleared events from the Payment domain's Kafka topic.
 *
 * <p>
 * When a payment clears (credit card authorization, check clearing, etc.),
 * the Payment domain publishes a PaymentCleared event to Kafka. This listener
 * consumes that event and creates a ReceivablePayment record in the Accounting
 * domain, making the payment available for application to invoices.
 *
 * <p>
 * <strong>Idempotency:</strong> Uses event.eventId as sourceEventId to ensure
 * duplicate events are ignored.
 *
 * <p>
 * <strong>Activation:</strong> Listener is conditionally activated when
 * {@code pos.accounting.kafka.enabled=true} in application.yml.
 *
 * @see PaymentApplicationService#handlePaymentCleared
 * @see com.positivity.accounting.internal.entity.ReceivablePayment
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class PaymentEventListenerConfig {

    private final ObjectMapper objectMapper;
    private final PaymentApplicationService paymentApplicationService;

    /**
     * Kafka listener for PaymentCleared events from the Payment domain.
     *
     * <p>Deserializes the JSON message into a {@link PaymentClearedEvent},
     * validates required fields, and delegates to
     * {@link PaymentApplicationService#handlePaymentCleared} for business logic.
     *
     * <p><strong>Error Handling:</strong>
     * <ul>
     *   <li>Deserialization errors: logged and event skipped</li>
     *   <li>Validation errors: logged and event skipped</li>
     *   <li>Service errors: logged and rethrown for Kafka retry/DLQ handling</li>
     *   <li>Idempotency: duplicate events silently ignored (logged at DEBUG)</li>
     * </ul>
     *
     * @param message raw JSON message from Kafka topic
     */
    @KafkaListener(
            topics = "${pos.accounting.kafka.payments-topic:payment.cleared.v1}",
            groupId = "${pos.accounting.kafka.consumer-group:pos-accounting-payments}")
    public void onPaymentCleared(@NonNull String message) {
        try {
            PaymentClearedEvent event = objectMapper.readValue(message, PaymentClearedEvent.class);

            log.debug("Received PaymentCleared event: {}", event.getEventId());

            // Validate required fields
            event.validate();

            // Process event (idempotent via sourceEventId check)
            paymentApplicationService.handlePaymentCleared(
                    event.getPaymentId(),
                    event.getCustomerId(),
                    event.getCurrencyCode(),
                    event.getAmount(),
                    event.getClearedAt(),
                    event.getEventId() // sourceEventId for idempotency
            );

            log.info("Successfully processed PaymentCleared event {} for payment {}",
                    event.getEventId(), event.getPaymentId());

        } catch (JacksonException e) {
            log.error("Failed to deserialize PaymentCleared Kafka message: {}", message, e);
            // Skip unparseable messages (don't rethrow — would cause infinite retry loop)

        } catch (IllegalArgumentException e) {
            log.error("Invalid PaymentCleared event from Kafka message: {}", message, e);
            // Skip invalid events (don't rethrow to prevent retry loop)

        } catch (Exception e) {
            log.error("Error processing PaymentCleared event from Kafka: {}", message, e);
            // Rethrow to trigger Kafka retry / dead-letter-topic handling
            throw new RuntimeException("Failed to process PaymentCleared Kafka event", e);
        }
    }
}
