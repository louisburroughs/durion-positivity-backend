package com.positivity.accounting.internal.config;

import com.positivity.accounting.internal.dto.PaymentClearedEvent;
import com.positivity.accounting.service.PaymentApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Message listener configuration for payment events (CAP:051 Story #114).
 * Listens for PaymentCleared events from Payment domain.
 * 
 * <p>
 * When a payment clears (credit card authorization, check clearing, etc.),
 * the Payment domain emits a PaymentCleared event. This listener consumes
 * that event and creates a ReceivablePayment record in the Accounting domain,
 * making the payment available for application to invoices.
 * 
 * <p>
 * <strong>Idempotency:</strong> Uses event.eventId as sourceEventId to ensure
 * duplicate events are ignored.
 * 
 * <p>
 * <strong>Activation:</strong> Listener is conditionally activated when
 * pos.accounting.event-listener.enabled=true in application.yml. This allows
 * graceful compilation without Kafka dependency until messaging infrastructure
 * is configured.
 * 
 * <p>
 * <strong>TODO:</strong> Replace Spring @EventListener with Kafka/RabbitMQ
 * listener annotations once message broker is configured. For now, this uses
 * Spring's internal event bus for testing/development.
 * 
 * @see PaymentApplicationService#handlePaymentCleared
 * @see com.positivity.accounting.internal.entity.ReceivablePayment
 */
@Configuration
@ConditionalOnProperty(prefix = "pos.accounting.event-listener", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PaymentEventListenerConfig {

    /**
     * Spring event listener for PaymentCleared events.
     * 
     * <p>
     * <strong>TEMPORARY IMPLEMENTATION:</strong> Uses Spring's internal
     * ApplicationEventPublisher for development. Will be replaced with
     * Kafka/RabbitMQ listener when message broker is configured.
     * 
     * <p>
     * Consumes events and delegates to PaymentApplicationService for
     * business logic execution.
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class PaymentEventListener {

        private final PaymentApplicationService paymentApplicationService;

        /**
         * Listen for PaymentCleared events via Spring's internal event bus.
         * 
         * <p>
         * <strong>Error Handling:</strong>
         * <ul>
         * <li>Validation errors: logged and event skipped</li>
         * <li>Service errors: logged and rethrown for potential retry</li>
         * <li>Idempotency: duplicate events silently ignored (logged at DEBUG)</li>
         * </ul>
         * 
         * <p>
         * <strong>TODO:</strong> Replace with @KafkaListener or @RabbitListener
         * when message broker is configured. For now, events can be published
         * programmatically via ApplicationEventPublisher for testing.
         * 
         * @param event PaymentCleared event from Payment domain
         */
        @EventListener
        public void onPaymentCleared(PaymentClearedEvent event) {
            try {
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

            } catch (IllegalArgumentException e) {
                log.error("Invalid PaymentCleared event: {}", event, e);
                // Skip invalid events (don't rethrow to prevent retry loop)

            } catch (Exception e) {
                log.error("Error processing PaymentCleared event: {}", event, e);
                // Rethrow to trigger retry mechanism (if configured)
                throw e;
            }
        }
    }
}
