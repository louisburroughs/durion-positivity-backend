package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.VendorBillGLPostingEvent;
import com.positivity.accounting.internal.exception.GLPostingException;
import com.positivity.accounting.internal.service.EventIngestionServiceImpl;
import com.positivity.accounting.service.EventIngestionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event handler for vendor bill GL posting events.
 *
 * <p>
 * Listens to {@link VendorBillGLPostingEvent} and creates accounting events for
 * journal entry generation. The posting engine will create:
 * <ul>
 * <li>Dr Inventory Account (for inventory items)</li>
 * <li>Dr Expense Account (for non-inventory items)</li>
 * <li>Cr Accounts Payable</li>
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
 * @see EventIngestionServiceImpl
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorBillGLPostingEventHandler {

    private final EventIngestionService eventIngestionService;

    /**
     * Handle vendor bill GL posting event → create accounting event for journal
     * entry generation.
     *
     * @param event Vendor bill GL posting event
     */
    @EventListener
    @Transactional
    public void onVendorBillGLPosting(@NonNull VendorBillGLPostingEvent event) {
        log.info(
                "Received VendorBillGLPostingEvent | eventId={} | billId={} | amount={}",
                event.getEventId(),
                event.getVendorBillId(),
                event.getTotalAmount());

        try {
            // Convert domain event to accounting event payload
            Map<String, Object> accountingEventPayload = createAccountingEventPayload(event);

            // Submit to event ingestion service for posting engine processing
            AccountingEventResponse response = eventIngestionService.submitEvent(accountingEventPayload);

            log.info(
                    "GL posting event submitted | billId={} | accountingEventId={} | status={}",
                    event.getVendorBillId(),
                    response.getEventId(),
                    response.getStatus());

        } catch (Exception e) {
            log.error(
                    "Failed to process VendorBillGLPostingEvent | billId={} | eventId={} | error={}",
                    event.getVendorBillId(),
                    event.getEventId(),
                    e.getMessage(),
                    e);
            // In production, this would trigger a retry mechanism or dead letter queue
            throw new GLPostingException("GL posting failed for bill: " + event.getVendorBillId(), e);
        }
    }

    /**
     * Convert VendorBillGLPostingEvent to AccountingEvent payload format.
     *
     * The payload structure follows the accounting event schema with:
     * - eventType: VENDOR_BILL_GL_POSTING
     * - sourceSystem: POS (Durion Positivity backend)
     * - transactionDate: Bill date
     * - payload: Vendor bill details including line items with isInventoryItem flag
     *
     * @param event Vendor bill GL posting event
     * @return AccountingEvent payload map
     */
    private Map<String, Object> createAccountingEventPayload(@NonNull VendorBillGLPostingEvent event) {
        Map<String, Object> payload = new HashMap<>();

        // Event metadata
        payload.put("eventId", event.getEventId());
        payload.put("organizationId", event.getOrganizationId());
        payload.put("eventType", "VENDOR_BILL_GL_POSTING");
        payload.put("sourceSystem", "POS");
        payload.put("transactionDate", event.getBillDate());

        // Vendor bill details
        Map<String, Object> billDetails = new HashMap<>();
        billDetails.put("vendorBillId", event.getVendorBillId());
        billDetails.put("vendorId", event.getVendorId());
        billDetails.put("vendorName", event.getVendorName());
        billDetails.put("purchaseOrderId", event.getPurchaseOrderId());
        billDetails.put("purchaseOrderNumber", event.getPurchaseOrderNumber());
        billDetails.put("billNumber", event.getBillNumber());
        billDetails.put("billDate", event.getBillDate());
        billDetails.put("totalAmount", event.getTotalAmount());

        // Line items with inventory classification for GL account determination
        List<Map<String, Object>> lineItems = event.getLineItems().stream()
                .map(line -> {
                    Map<String, Object> lineItem = new HashMap<>();
                    lineItem.put("productId", line.getProductId());
                    lineItem.put("sku", line.getSku());
                    lineItem.put("description", line.getDescription());
                    lineItem.put("quantity", line.getQuantity());
                    lineItem.put("unitPrice", line.getUnitPrice());
                    lineItem.put("lineTotal", line.getLineTotal());
                    // CRITICAL: Preserve isInventoryItem flag for account determination
                    // true → Dr Inventory Asset, false → Dr Expense
                    lineItem.put("isInventoryItem", line.isInventoryItem());
                    return lineItem;
                })
                .toList();

        billDetails.put("lineItems", lineItems);
        payload.put("payload", billDetails);

        Map<String, String> dimensions = event.getDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            payload.put("dimensions", Map.of());
        } else {
            payload.put("dimensions", new HashMap<>(dimensions));
        }

        return payload;
    }
}
