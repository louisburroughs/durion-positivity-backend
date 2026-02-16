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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
            idempotencyService.registerInvoiceKey(idempotencyKey, response.getInvoiceId());
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

        // CAP:007 - Load parts avoiding duplicates
        // First, get all parts associated with workorder services
        List<WorkorderPart> parts = new ArrayList<>();
        parts.addAll(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId));
        
        // Then, get standalone parts (those with direct workorder reference but no service)
        // This query ensures we don't get duplicates when a part has both workorder and workOrderService set
        parts.addAll(workorderPartRepository.findByWorkorderIdAndWorkOrderServiceIsNull(workorderId));

        // Deduplicate by part ID as a safety measure (in case parts have both references)
        // This protects against over-billing if the same part appears in both queries
        // Use explicit ID-based deduplication to ensure correctness regardless of equals/hashCode implementation
        var seenIds = new HashSet<UUID>();
        parts = parts.stream()
                .filter(part -> {
                    UUID id = part.getId();
                    if (id == null) {
                        // If a part has a null ID, skip deduplication for it to avoid silently dropping items
                        log.warn("WorkorderPart with null id encountered while building invoice line items for workorder {}", workorderId);
                        return true;
                    }
                    return seenIds.add(id);
                })
                .toList();

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
