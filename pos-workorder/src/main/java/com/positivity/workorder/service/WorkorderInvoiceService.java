package com.positivity.workorder.service;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.workorder.internal.client.InvoiceClient;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.exception.InvalidWorkorderStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for generating invoice drafts from completed workorders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderInvoiceService {

    private final WorkorderRepository workorderRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final IdempotencyService idempotencyService;
    private final InvoiceClient invoiceClient;

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

        workorder.setInvoiceId(response.getInvoiceId());
        workorderRepository.save(workorder);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyService.registerInvoiceKey(idempotencyKey, response.getInvoiceId());
                // Force flush so any unique-constraint violation is raised within this
                // try/catch
                try {
                    TransactionAspectSupport.currentTransactionStatus().flush();
                } catch (NoTransactionException | IllegalStateException e) {
                    // No active transaction (e.g., in unit tests) - flush not needed
                    log.debug("Flush skipped: no active transaction");
                }
            } catch (DataIntegrityViolationException e) {
                // Race condition: another request already registered this key
                // The unique constraint violation means another transaction has committed the
                // key.
                // Mark our transaction for rollback to prevent persisting the duplicate
                // invoice reference.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

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

        return response;
    }

    @NonNull
    private InvoiceGenerationResponse buildExistingResponse(@NonNull Workorder workorder, @NonNull UUID invoiceId) {
        BigDecimal subtotal = calculateSubtotal(workorder.getId());
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal totalAmount = subtotal.add(taxAmount);

        return InvoiceGenerationResponse.builder()
                .invoiceId(invoiceId)
                .status("DRAFT")
                .workorderId(workorder.getId())
                .estimateId(workorder.getEstimateId())
                .approvalId(workorder.getApprovalId())
                .subtotal(subtotal)
                .taxAmount(taxAmount)
                .totalAmount(totalAmount)
                .createdAt(resolveCreatedAt(workorder))
                .build();
    }

    @NonNull
    private List<InvoiceLineItem> buildLineItems(@NonNull UUID workorderId) {
        List<InvoiceLineItem> lineItems = new ArrayList<>();

        workorderServiceRepository.findByWorkOrder_Id(workorderId)
                .forEach(service -> lineItems.add(InvoiceLineItem.builder()
                        .description(service.getDescription() == null ? "Service item" : service.getDescription())
                        .quantity(service.getQuantity() == null ? BigDecimal.ONE : service.getQuantity())
                        .unitPrice(service.getUnitPrice() == null ? BigDecimal.ZERO : service.getUnitPrice())
                        .amount(resolveLineAmount(
                                service.getLineTotal(),
                                service.getQuantity(),
                                service.getUnitPrice()))
                        .build()));

        List<WorkorderPart> parts = new ArrayList<>();
        parts.addAll(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId));
        parts.addAll(workorderPartRepository.findByWorkorderId(workorderId));

        parts.forEach(part -> lineItems.add(InvoiceLineItem.builder()
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
    private BigDecimal calculateSubtotal(@NonNull UUID workorderId) {
        return buildLineItems(workorderId).stream()
                .map(InvoiceLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    @NonNull
    private Instant resolveCreatedAt(@NonNull Workorder workorder) {
        if (workorder.getCompletedAt() != null) {
            return workorder.getCompletedAt();
        }

        LocalDateTime updatedAt = workorder.getUpdatedAt();
        if (updatedAt != null) {
            return updatedAt.toInstant(ZoneOffset.UTC);
        }

        return Instant.now();
    }
}
