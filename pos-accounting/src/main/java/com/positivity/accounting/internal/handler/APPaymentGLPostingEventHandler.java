package com.positivity.accounting.internal.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.service.EventIngestionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Event handler for AP payment GL posting events.
 *
 * <p>
 * Listens to {@link APPaymentGLPostingEvent} and creates accounting events for
 * journal entry generation. The posting engine will create:
 * <ul>
 * <li>Dr Accounts Payable (reduce liability for each allocated bill)</li>
 * <li>Cr Cash / Bank (payment outflow)</li>
 * </ul>
 *
 * <p>
 * If the payment has processing fees, an additional entry is created:
 * <ul>
 * <li>Dr Processing Fee Expense</li>
 * <li>Cr Cash / Bank</li>
 * </ul>
 *
 * <p>
 * This handler converts the domain event into an
 * {@link com.positivity.accounting.internal.entity.AccountingEvent}
 * which is then processed by the posting engine to:
 * <ol>
 * <li>Load active posting rule set for the event</li>
 * <li>Evaluate rules to determine GL account mappings</li>
 * <li>Create balanced journal entry</li>
 * <li>Auto-post if rule set allows</li>
 * </ol>
 *
 * @see EventIngestionService
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class APPaymentGLPostingEventHandler {

    private final EventIngestionService eventIngestionService;

    /**
     * Handle AP payment GL posting event → create accounting event for journal
     * entry generation.
     *
     * @param event AP payment GL posting event
     */
    @EventListener
    @Transactional
    public void onAPPaymentGLPosting(@NonNull APPaymentGLPostingEvent event) {
        log.info("Received APPaymentGLPostingEvent | eventId={} | paymentId={} | paymentRef={} | amount={}",
                event.getEventId(), event.getPaymentId(), event.getPaymentRef(), event.getGrossAmount());

        try {
            Map<String, Object> accountingEventPayload = createAccountingEventPayload(event);

            AccountingEventResponse response = eventIngestionService.submitEvent(accountingEventPayload);

            log.info("AP payment GL posting event submitted | paymentId={} | accountingEventId={} | status={}",
                    event.getPaymentId(), response.getEventId(), response.getStatus());

        } catch (Exception e) {
            log.error("Failed to process APPaymentGLPostingEvent | paymentId={} | eventId={} | error={}",
                    event.getPaymentId(), event.getEventId(), e.getMessage(), e);
            throw new RuntimeException("GL posting failed for AP payment: " + event.getPaymentId(), e);
        }
    }

    /**
     * Convert APPaymentGLPostingEvent to AccountingEvent payload format.
     *
     * <p>
     * The payload structure follows the accounting event schema with:
     * <ul>
     * <li>eventType: AP_PAYMENT_GL_POSTING</li>
     * <li>sourceSystem: POS (Durion POS backend)</li>
     * <li>transactionDate: gateway timestamp (or event creation time)</li>
     * <li>payload: payment details including allocations per vendor bill</li>
     * </ul>
     *
     * @param event AP payment GL posting event
     * @return AccountingEvent payload map
     */
    private Map<String, Object> createAccountingEventPayload(@NonNull APPaymentGLPostingEvent event) {
        Map<String, Object> payload = new HashMap<>();

        // Event metadata
        payload.put("eventId", event.getEventId());
        payload.put("eventType", "AP_PAYMENT_GL_POSTING");
        payload.put("sourceSystem", "POS");
        payload.put("transactionDate", event.getGatewayTimestamp());

        // Payment details
        Map<String, Object> paymentDetails = new HashMap<>();
        paymentDetails.put("paymentId", event.getPaymentId());
        paymentDetails.put("paymentRef", event.getPaymentRef());
        paymentDetails.put("vendorId", event.getVendorId());
        paymentDetails.put("vendorName", event.getVendorName());
        paymentDetails.put("grossAmount", event.getGrossAmount());
        paymentDetails.put("feeAmount", event.getFeeAmount());
        paymentDetails.put("netAmount", event.getNetAmount());
        paymentDetails.put("unappliedAmount", event.getUnappliedAmount());
        paymentDetails.put("currency", event.getCurrency());
        paymentDetails.put("paymentMethod", event.getPaymentMethod());
        paymentDetails.put("gatewayTransactionId", event.getGatewayTransactionId());
        paymentDetails.put("gatewayTimestamp", event.getGatewayTimestamp());
        paymentDetails.put("memo", event.getMemo());

        // Allocations for per-bill AP reduction entries
        List<Map<String, Object>> allocationItems = event.getAllocations().stream()
                .map(alloc -> {
                    Map<String, Object> allocItem = new HashMap<>();
                    allocItem.put("allocationId", alloc.getAllocationId());
                    allocItem.put("vendorBillId", alloc.getVendorBillId());
                    allocItem.put("appliedAmount", alloc.getAppliedAmount());
                    allocItem.put("allocationSequence", alloc.getAllocationSequence());
                    return allocItem;
                })
                .collect(Collectors.toList());

        paymentDetails.put("allocations", allocationItems);
        payload.put("payload", paymentDetails);

        // Dimensions for multi-dimensional accounting (optional, can be added later)
        Map<String, Object> dimensions = new HashMap<>();
        payload.put("dimensions", dimensions);

        return payload;
    }
}
