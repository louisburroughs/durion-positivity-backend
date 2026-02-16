package com.positivity.workorder.service;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.workorder.internal.client.InvoiceClient;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderItemStatus;
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
import java.util.ArrayList;
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
public class WorkorderInvoiceService {

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

        workorder.setInvoiceId(response.getInvoiceId());
        workorderRepository.save(workorder);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.registerInvoiceKey(idempotencyKey, response.getInvoiceId());
        }

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

        List<WorkorderPart> parts = new ArrayList<>();
        parts.addAll(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId));
        parts.addAll(workorderPartRepository.findByWorkorderId(workorderId));

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
}
