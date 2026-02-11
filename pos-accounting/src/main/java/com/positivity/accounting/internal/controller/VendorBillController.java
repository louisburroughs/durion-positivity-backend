package com.positivity.accounting.internal.controller;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.service.VendorBillService;
import com.positivity.events.EmitEvent;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Controller for Vendor Bill lifecycle management.
 * Exposes endpoints for bill creation, three-way matching, and exception
 * resolution.
 * 
 * @see VendorBillService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/accounting/vendor-bills")
@RequiredArgsConstructor
public class VendorBillController {

    private final VendorBillService vendorBillService;

    /**
     * Create a vendor bill from a goods received event.
     * 
     * POST /api/v1/accounting/vendor-bills
     * 
     * @param event the goods received event payload
     * @return created bill response with 201 status
     */
    @PostMapping
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_CREATE", apiVersion = "1")
    public ResponseEntity<VendorBillResponse> createBillFromGoodsReceivedEvent(
            @NonNull @Valid @RequestBody GoodsReceivedEvent event) {
        log.info("Received request to create vendor bill from goods received event | eventId={} | vendorId={}",
                event.getEventId(), event.getVendorId());

        VendorBillResponse response = vendorBillService.handleGoodsReceivedEvent(event);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Process vendor invoice and perform three-way match.
     * 
     * POST /api/v1/accounting/vendor-bills/match
     * 
     * @param event the vendor invoice received event payload
     * @return bill response with match result (201 if successful, 400 if exception)
     */
    @PostMapping("/match")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH", apiVersion = "1")
    public ResponseEntity<VendorBillResponse> matchVendorInvoice(
            @NonNull @Valid @RequestBody VendorInvoiceReceivedEvent event) {
        log.info("Received request to perform three-way match | eventId={} | invoiceRef={}",
                event.getEventId(), event.getInvoiceReference());

        VendorBillResponse response = vendorBillService.handleVendorInvoiceReceivedEvent(event);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Resolve a bill match exception (ACCEPT/VOID/CORRECT).
     * 
     * POST /api/v1/accounting/vendor-bills/{billId}/resolve-exception
     * 
     * @param billId  the vendor bill ID
     * @param request the exception resolution request
     * @return updated bill response with new status
     */
    @PostMapping("/{billId}/resolve-exception")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE", apiVersion = "1")
    public ResponseEntity<VendorBillResponse> resolveMatchException(
            @NonNull @PathVariable UUID billId,
            @NonNull @Valid @RequestBody ExceptionResolutionRequest request) {
        log.info("Received request to resolve match exception | billId={} | action={}",
                billId, request.getResolutionAction());

        VendorBillResponse response = vendorBillService.resolveMatchException(
                billId,
                request.getResolutionAction(),
                request.getReason(),
                request.getOperatorId());

        return ResponseEntity.ok(response);
    }

    /**
     * Get vendor bill by bill ID.
     * 
     * GET /api/v1/accounting/vendor-bills/{billId}
     * 
     * @param billId the vendor bill ID
     * @return bill response with 200 status, or 404 if not found
     */
    @GetMapping("/{billId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_GET", apiVersion = "1")
    public ResponseEntity<VendorBillResponse> getBillById(
            @NonNull @PathVariable UUID billId) {
        log.info("Received request to retrieve vendor bill | billId={}", billId);

        return vendorBillService.getBillById(billId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get vendor bill by origin event ID.
     * 
     * GET /api/v1/accounting/vendor-bills/event/{eventId}
     * 
     * @param eventId the origin event ID (from GoodsReceivedEvent)
     * @return bill response with 200 status, or 404 if not found
     */
    @GetMapping("/event/{eventId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_BILL_GET_BY_EVENT", apiVersion = "1")
    public ResponseEntity<VendorBillResponse> getBillByOriginEventId(
            @NonNull @PathVariable UUID eventId) {
        log.info("Received request to retrieve vendor bill by origin event | eventId={}", eventId);

        return vendorBillService.getBillByOriginEventId(eventId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * DTO for exception resolution requests.
     */
    public static class ExceptionResolutionRequest {
        private String resolutionAction; // ACCEPT, VOID, CORRECT
        private String reason;
        private String operatorId;

        public ExceptionResolutionRequest() {
        }

        public ExceptionResolutionRequest(@NonNull String resolutionAction, @NonNull String reason,
                @NonNull String operatorId) {
            this.resolutionAction = resolutionAction;
            this.reason = reason;
            this.operatorId = operatorId;
        }

        @NonNull
        public String getResolutionAction() {
            return resolutionAction;
        }

        public void setResolutionAction(@NonNull String resolutionAction) {
            this.resolutionAction = resolutionAction;
        }

        @NonNull
        public String getReason() {
            return reason;
        }

        public void setReason(@NonNull String reason) {
            this.reason = reason;
        }

        @NonNull
        public String getOperatorId() {
            return operatorId;
        }

        public void setOperatorId(@NonNull String operatorId) {
            this.operatorId = operatorId;
        }
    }
}
