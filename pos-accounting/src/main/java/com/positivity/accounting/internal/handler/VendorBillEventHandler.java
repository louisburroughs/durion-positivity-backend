package com.positivity.accounting.internal.handler;

import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.service.VendorBillService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Event handler for vendor bill lifecycle events (Issue #130).
 * 
 * <p>
 * Listens to:
 * <ul>
 * <li>{@link GoodsReceivedEvent} - Creates bill in PENDING_RECEIPT_MATCH</li>
 * <li>{@link VendorInvoiceReceivedEvent} - Performs three-way match
 * validation</li>
 * </ul>
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorBillEventHandler {

    private final VendorBillService vendorBillService;

    /**
     * Handle goods received event → create vendor bill in PENDING_RECEIPT_MATCH.
     * 
     * @param event Goods received event from inventory/purchasing system
     */
    @EventListener
    @Transactional
    public void onGoodsReceived(@NonNull GoodsReceivedEvent event) {
        log.debug("Received GoodsReceivedEvent | eventId={} | vendorId={}",
                event.getEventId(), event.getVendorId());

        try {
            VendorBillResponse response = vendorBillService.handleGoodsReceivedEvent(event);
            log.info("Vendor bill created from goods receipt | billId={} | status={}",
                    response.getVendorBillId(), response.getStatus());
        } catch (Exception e) {
            log.error("Failed to create vendor bill from goods receipt | eventId={} | error={}",
                    event.getEventId(), e.getMessage(), e);
            // In production, would publish failure event for DLQ/retry
            throw e;
        }
    }

    /**
     * Handle vendor invoice received event → three-way match validation.
     * 
     * @param event Vendor invoice received event from AP system
     */
    @EventListener
    @Transactional
    public void onVendorInvoiceReceived(@NonNull VendorInvoiceReceivedEvent event) {
        log.debug("Received VendorInvoiceReceivedEvent | eventId={} | vendorId={} | invoiceRef={}",
                event.getEventId(), event.getVendorId(), event.getInvoiceReference());

        try {
            VendorBillResponse response = vendorBillService.handleVendorInvoiceReceivedEvent(event);
            log.info("Three-way match completed | billId={} | status={}",
                    response.getVendorBillId(), response.getStatus());
        } catch (Exception e) {
            log.error("Failed to process vendor invoice | eventId={} | error={}",
                    event.getEventId(), e.getMessage(), e);
            // In production, would publish failure event for DLQ/retry
            throw e;
        }
    }
}
