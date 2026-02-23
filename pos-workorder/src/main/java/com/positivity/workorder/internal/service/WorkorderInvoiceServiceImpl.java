package com.positivity.workorder.internal.service;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.workorder.internal.client.InvoiceClient;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.InvalidWorkorderStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkorderInvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for generating invoice drafts from completed workorders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderInvoiceServiceImpl implements WorkorderInvoiceService {

    private final WorkorderRepository workorderRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final IdempotencyService idempotencyService;
    private final InvoiceClient invoiceClient;

    // Statuses excluded from billable totals (aligned with WorkorderStateMachine)
    private static final Set<WorkorderItemStatus> EXCLUDED_BILLABLE_TOTAL_STATUSES = Set.of(
            WorkorderItemStatus.CANCELLED);

    @Transactional
    @NonNull
    public InvoiceGenerationResponse generateInvoice(
            @NonNull UUID workorderId,
            @Nullable String idempotencyKey) {

        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        if (workorder.getStatus() != WorkorderStatus.COMPLETED) {
            throw new InvalidWorkorderStateException(workorderId, workorder.getStatus().name(), "COMPLETED");
        }

        if (workorder.getInvoiceId() != null) {
            log.info("Invoice already generated for workorder {} as invoice {}", workorderId, workorder.getInvoiceId());
            return buildExistingResponse(workorder, workorder.getInvoiceId());
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingInvoiceId = idempotencyService.getExistingInvoiceId(idempotencyKey);
            if (existingInvoiceId.isPresent()) {
                UUID invoiceId = existingInvoiceId.get();
                if (workorder.getInvoiceId() == null) {
                    workorder.setInvoiceId(invoiceId);
                    workorderRepository.save(workorder);
                }
                log.info("Idempotent invoice generation replay for key {} and workorder {}, returning invoice {}",
                        idempotencyKey, workorderId, invoiceId);
                return buildExistingResponse(workorder, invoiceId);
            }
        }

        List<InvoiceLineItem> lineItems = buildLineItems(workorderId);

        InvoiceCreationRequest request = InvoiceCreationRequest.builder()
                .workorderId(workorder.getId())
                .estimateId(workorder.getEstimateId())
                .approvalId(workorder.getApprovalId())
                .idempotencyKey(idempotencyKey)
                .lineItems(lineItems)
                .build();

        InvoiceGenerationResponse response = invoiceClient.createInvoice(request);

        if (response.getInvoiceId() == null) {
            throw new IllegalStateException("Invoice service returned response without invoiceId for workorder "
                    + workorderId);
        }

        if (response.getWorkorderId() == null) {
            response.setWorkorderId(workorder.getId());
        }
        if (response.getEstimateId() == null) {
            response.setEstimateId(workorder.getEstimateId());
        }
        if (response.getApprovalId() == null) {
            response.setApprovalId(workorder.getApprovalId());
        }

        // Register idempotency key first (if provided) to detect race conditions before
        // persisting
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.registerInvoiceKey(idempotencyKey, response.getInvoiceId());
                // Force flush so any unique-constraint violation is raised within this
                // try/catch
                try {
                    TransactionAspectSupport.currentTransactionStatus().flush();
                } catch (NoTransactionException e) {
                    // No active transaction (e.g., in unit tests) - flush not needed
                    log.debug("Flush skipped: no active transaction");
                }
            } catch (DataIntegrityViolationException e) {
                // Race condition: another request already registered this key
                // The unique constraint violation means another transaction has committed the
                // key.
                // Mark our transaction for rollback to prevent persisting the duplicate
                // invoice reference.
                try {
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                } catch (NoTransactionException ex) {
                    // No active transaction (e.g., in unit tests) - rollback not needed
                    log.debug("Rollback skipped: no active transaction");
                }

                // Retrieve the existing invoice that won the race
                // Note: The DataIntegrityViolationException indicates the other transaction has
                // committed, so the invoice should be visible with READ_COMMITTED isolation.
                Optional<UUID> existingInvoiceId = idempotencyService.getExistingInvoiceId(idempotencyKey);
                if (existingInvoiceId.isPresent()) {
                    log.warn(
                            "Race condition detected: idempotency key {} already registered for invoice {}, returning existing invoice",
                            idempotencyKey, existingInvoiceId.get());
                    // Return the existing invoice to maintain idempotency semantics
                    // The transaction will be rolled back, preventing the duplicate workorder
                    // invoice reference
                    return buildExistingResponse(workorder, existingInvoiceId.get());
                } else {
                    // This should not happen - if DataIntegrityViolationException was thrown,
                    // the key must exist. This indicates a serious data inconsistency.
                    log.error("Race condition detected but no existing invoice found for idempotency key {}",
                            idempotencyKey);
                    throw new IllegalStateException(
                            "Idempotency key collision but no existing invoice found: " + idempotencyKey, e);
                }
            }
        }

        // Persist the workorder invoice link after successful idempotency key
        // registration
        workorder.setInvoiceId(response.getInvoiceId());
        workorderRepository.save(workorder);

        return response;
    }

    @NonNull
    private InvoiceGenerationResponse buildExistingResponse(@NonNull Workorder workorder, @NonNull UUID invoiceId) {
        InvoiceGenerationResponse invoiceDetails = invoiceClient.getInvoice(invoiceId);

        // Ensure workorder links are populated (invoice service might not return them)
        if (invoiceDetails.getWorkorderId() == null) {
            invoiceDetails.setWorkorderId(workorder.getId());
        }
        if (invoiceDetails.getEstimateId() == null) {
            invoiceDetails.setEstimateId(workorder.getEstimateId());
        }
        if (invoiceDetails.getApprovalId() == null) {
            invoiceDetails.setApprovalId(workorder.getApprovalId());
        }

        return invoiceDetails;
    }

    @NonNull
    private List<InvoiceLineItem> buildLineItems(@NonNull UUID workorderId) {
        List<InvoiceLineItem> lineItems = new ArrayList<>();

        // Filter to only billable services (exclude CANCELLED items)
        workorderServiceRepository.findByWorkOrder_Id(workorderId).stream()
                .filter(service -> !isExcludedFromBillableTotal(service.getStatus()))
                .forEach(service -> lineItems.add(InvoiceLineItem.builder()
                        .description(service.getDescription() == null ? "Service item" : service.getDescription())
                        .quantity(service.getQuantity() == null ? BigDecimal.ONE : service.getQuantity())
                        .unitPrice(service.getUnitPrice() == null ? BigDecimal.ZERO : service.getUnitPrice())
                        .amount(resolveLineAmount(
                                service.getLineTotal(),
                                service.getQuantity(),
                                service.getUnitPrice()))
                        .build()));

        // CAP:007 - Load parts avoiding duplicates
        // First, get all parts associated with workorder services
        List<WorkorderPart> parts = new ArrayList<>();
        parts.addAll(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId));

        // Then, get standalone parts (those with direct workorder reference but no
        // service)
        // This query ensures we don't get duplicates when a part has both workorder and
        // workOrderService set
        parts.addAll(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId));

        // Deduplicate by part ID as a safety measure (in case parts have both
        // references)
        // This protects against over-billing if the same part appears in both queries
        // Use explicit ID-based deduplication to ensure correctness regardless of
        // equals/hashCode implementation
        var seenIds = new HashSet<UUID>();
        parts = parts.stream()
                .filter(part -> {
                    UUID id = part.getId();
                    if (id == null) {
                        // If a part has a null ID, skip deduplication for it to avoid silently dropping
                        // items
                        log.warn(
                                "WorkorderPart with null id encountered while building invoice line items for workorder {}",
                                workorderId);
                        return true;
                    }
                    return seenIds.add(id);
                })
                .toList();

        // Filter to only billable parts (exclude CANCELLED items)
        parts.stream()
                .filter(part -> !isExcludedFromBillableTotal(part.getStatus()))
                .forEach(part -> lineItems.add(InvoiceLineItem.builder()
                        .description(part.getDescription() == null ? "Part item" : part.getDescription())
                        .quantity(part.getQuantity() == null ? BigDecimal.ONE : part.getQuantity())
                        .unitPrice(part.getUnitPrice() == null ? BigDecimal.ZERO : part.getUnitPrice())
                        .amount(resolveLineAmount(
                                part.getLineTotal(),
                                part.getQuantity(),
                                part.getUnitPrice()))
                        .build()));

        return lineItems;
    }

    @NonNull
    private BigDecimal resolveLineAmount(
            @Nullable BigDecimal lineTotal,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice) {
        if (lineTotal != null) {
            return lineTotal;
        }

        BigDecimal safeQuantity = quantity == null ? BigDecimal.ONE : quantity;
        BigDecimal safeUnitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        return safeQuantity.multiply(safeUnitPrice);
    }

    /**
     * Determines if a workorder item status should be excluded from billable
     * totals.
     * Used to filter out items (like CANCELLED) that should not block invoice
     * generation.
     */
    private boolean isExcludedFromBillableTotal(@Nullable WorkorderItemStatus status) {
        return status != null && EXCLUDED_BILLABLE_TOTAL_STATUSES.contains(status);
    }
}
