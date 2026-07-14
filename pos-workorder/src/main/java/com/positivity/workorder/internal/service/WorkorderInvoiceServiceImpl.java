package com.positivity.workorder.internal.service;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.workorder.internal.config.InvoiceCommandPublisher;
import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.InvalidWorkorderStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.WorkorderInvoiceService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests invoice generation for completed workorders (ADR-0044 §4, #900 Phase 5.4).
 *
 * <p>Generation is asynchronous: this service publishes an {@code invoice.generation-requested}
 * command carrying the billable line items and returns a {@code PENDING} response; pos-invoice
 * creates the invoice and its {@code invoice.invoice.updated} fact links
 * {@code workorder.invoiceId} through the {@code ext_invoice} replica
 * ({@link InvoiceEventsListener}). Already-invoiced workorders answer from the replica.
 * Client retries with the same {@code Idempotency-Key} collapse to one deterministic command id,
 * so pos-invoice creates exactly one invoice per key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderInvoiceServiceImpl implements WorkorderInvoiceService {

    /** Response status while generation is in flight (ADR-0044 R4 pending state). */
    public static final String STATUS_PENDING = "PENDING";

    private final WorkorderRepository workorderRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final ExtInvoiceReplicaRepository extInvoiceReplicaRepository;
    private final ObjectProvider<InvoiceCommandPublisher> invoiceCommandPublisher;

    // Statuses excluded from billable totals (aligned with WorkorderStateMachine)
    private static final Set<WorkorderItemStatus> EXCLUDED_BILLABLE_TOTAL_STATUSES =
            Set.of(WorkorderItemStatus.CANCELLED);

    @Transactional
    @NonNull
    public InvoiceGenerationResponse generateInvoice(@NonNull UUID workorderId, @Nullable String idempotencyKey) {
        Workorder workorder = loadCompletedWorkorder(workorderId);

        if (workorder.getInvoiceId() != null) {
            log.info("Invoice already generated for workorder {} as invoice {}", workorderId, workorder.getInvoiceId());
            return buildExistingResponse(workorder, workorder.getInvoiceId());
        }

        InvoiceCommandPublisher publisher = invoiceCommandPublisher.getIfAvailable();
        if (publisher == null) {
            throw new IllegalStateException(
                    "Invoice generation is asynchronous (ADR-0044 #900) and requires the Kafka event feed;"
                            + " enable workorder.kafka.enabled");
        }

        InvoiceCreationRequest request = InvoiceCreationRequest.builder()
                .workorderId(workorder.getId())
                .estimateId(workorder.getEstimateId())
                .approvalId(workorder.getApprovalId())
                .locationId(workorder.getLocationId())
                .idempotencyKey(idempotencyKey)
                .lineItems(buildLineItems(workorder.getId()))
                .build();
        publisher.requestInvoiceGeneration(
                request, idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : workorderId.toString());

        return InvoiceGenerationResponse.builder()
                .workorderId(workorder.getId())
                .estimateId(workorder.getEstimateId())
                .approvalId(workorder.getApprovalId())
                .status(STATUS_PENDING)
                .build();
    }

    @NonNull
    private Workorder loadCompletedWorkorder(@NonNull UUID workorderId) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));
        if (workorder.getStatus() != WorkorderStatus.COMPLETED) {
            throw new InvalidWorkorderStateException(
                    workorderId, workorder.getStatus().name(), "COMPLETED");
        }
        return workorder;
    }

    @NonNull
    private InvoiceGenerationResponse buildExistingResponse(@NonNull Workorder workorder, @NonNull UUID invoiceId) {
        ExtInvoiceReplica replica =
                extInvoiceReplicaRepository.findById(invoiceId).orElse(null);
        InvoiceGenerationResponse.InvoiceGenerationResponseBuilder response = InvoiceGenerationResponse.builder()
                .invoiceId(invoiceId)
                .workorderId(workorder.getId())
                .estimateId(workorder.getEstimateId())
                .approvalId(workorder.getApprovalId());
        if (replica == null) {
            // Linked before the replica row arrived (or replay pending) — the id is authoritative.
            return response.status(STATUS_PENDING).build();
        }
        return response.status(replica.getStatus())
                .subtotal(replica.getSubtotal())
                .taxAmount(replica.getTax())
                .totalAmount(replica.getTotal())
                .createdAt(replica.getInvoiceCreatedAt())
                .build();
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
                                service.getLineTotal(), service.getQuantity(), service.getUnitPrice()))
                        .type("LABOR")
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
                        .amount(resolveLineAmount(part.getLineTotal(), part.getQuantity(), part.getUnitPrice()))
                        .type("PART")
                        .build()));

        return lineItems;
    }

    @NonNull
    private BigDecimal resolveLineAmount(
            @Nullable BigDecimal lineTotal, @Nullable BigDecimal quantity, @Nullable BigDecimal unitPrice) {
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
