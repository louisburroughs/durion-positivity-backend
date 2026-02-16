package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.TaxServiceClient;
import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.InvoiceAdjustmentResponse;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.dto.InvoiceItemResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceAdjustment;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.service.InvoiceService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class InvoiceServiceImpl implements InvoiceService {

    private static final String SYSTEM_USER = "system";

    private final InvoiceRepository invoiceRepository;
    private final TaxServiceClient taxServiceClient;

    public InvoiceServiceImpl(
            @NonNull InvoiceRepository invoiceRepository,
            @NonNull TaxServiceClient taxServiceClient) {
        this.invoiceRepository = invoiceRepository;
        this.taxServiceClient = taxServiceClient;
    }

    @Override
    @NonNull
    public InvoiceGenerationResponse createInvoice(@NonNull InvoiceGenerationRequest request) {
        if (request.getWorkorderId() == null) {
            throw new IllegalArgumentException("workorderId is required");
        }

        InvoiceCreationRequest creationRequest = InvoiceCreationRequest.builder()
                .workorderId(request.getWorkorderId())
                .lineItems(List.of())
                .build();

        return createInvoice(creationRequest);
    }

    @Override
    @NonNull
    public InvoiceGenerationResponse createInvoice(@NonNull InvoiceCreationRequest request) {
        if (request.getWorkorderId() == null) {
            throw new IllegalArgumentException("workorderId is required");
        }

        return invoiceRepository.findByWorkorderId(request.getWorkorderId())
                .map(this::toGenerationResponse)
                .orElseGet(() -> createNewInvoice(request));
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public InvoiceDetailsResponse getInvoice(@NonNull UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        return toDetailsResponse(invoice);
    }

    @Override
    @NonNull
    public InvoiceDetailsResponse applyAdjustment(@NonNull UUID invoiceId, @NonNull AdjustmentRequest request) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));

        validateDraftState(invoice);
        validateAdjustmentRequest(request);

        InvoiceAdjustment adjustment = new InvoiceAdjustment();
        adjustment.setType(Objects.requireNonNull(request.getType(), "adjustment type is required"));
        adjustment.setAmount(request.getAmount().setScale(4, RoundingMode.HALF_UP));
        adjustment.setReason(request.getReason().trim());
        adjustment.setAuthorizedBy(request.getAuthorizedBy().trim());

        invoice.addAdjustment(adjustment);
        recalculateTotals(invoice);

        Invoice saved = invoiceRepository.save(invoice);
        return toDetailsResponse(saved);
    }

    @Override
    @NonNull
    public InvoiceDetailsResponse finalizeInvoice(@NonNull UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));

        if (invoice.getStatus() == InvoiceStatus.FINALIZED) {
            return toDetailsResponse(invoice);
        }

        validateDraftState(invoice);

        if (invoice.getInvoiceNumber() == null || invoice.getInvoiceNumber().isBlank()) {
            invoice.setInvoiceNumber(generateInvoiceNumber(invoice));
        }

        invoice.setStatus(InvoiceStatus.FINALIZED);
        invoice.setFinalizedAt(Instant.now());
        invoice.setFinalizedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER));

        Invoice saved = invoiceRepository.save(invoice);
        return toDetailsResponse(saved);
    }

    @NonNull
    private InvoiceGenerationResponse createNewInvoice(@NonNull InvoiceCreationRequest request) {
        Invoice invoice = new Invoice();
        invoice.setWorkorderId(request.getWorkorderId());
        invoice.setEstimateId(request.getEstimateId());
        invoice.setApprovalId(request.getApprovalId());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setAdjustments(BigDecimal.ZERO);
        invoice.setAdjustmentsAmount(BigDecimal.ZERO);

        List<InvoiceLineItem> lineItems = request.getLineItems() == null ? List.of() : request.getLineItems();
        for (InvoiceLineItem sourceItem : lineItems) {
            InvoiceItem item = new InvoiceItem();
            String description = sourceItem.getDescription();
            item.setDescription(
                    description == null || description.isBlank()
                            ? "Invoice line item"
                            : description.trim());
            item.setQuantity(safeMoney(sourceItem.getQuantity(), BigDecimal.ONE));
            item.setUnitPrice(safeMoney(sourceItem.getUnitPrice(), BigDecimal.ZERO));
            item.setLineTotal(resolveLineTotal(sourceItem));
            invoice.addItem(item);
        }

        BigDecimal subtotal = invoice.getItems().stream()
                .map(InvoiceItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        invoice.setSubtotal(subtotal);
        recalculateTotals(invoice);

        Invoice saved = invoiceRepository.save(invoice);
        return toGenerationResponse(saved);
    }

    private void validateDraftState(@NonNull Invoice invoice) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidInvoiceStateException(
                    Objects.requireNonNull(invoice.getId(), "invoiceId is required"),
                    invoice.getStatus(),
                    InvoiceStatus.DRAFT);
        }
    }

    private void validateAdjustmentRequest(@NonNull AdjustmentRequest request) {
        if (request.getType() == null) {
            throw new IllegalArgumentException("adjustment type is required");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("adjustment amount must be greater than zero");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("adjustment reason is required");
        }
        if (request.getAuthorizedBy() == null || request.getAuthorizedBy().isBlank()) {
            throw new IllegalArgumentException("authorizedBy is required");
        }
    }

    private void recalculateTotals(@NonNull Invoice invoice) {
        BigDecimal adjustmentTotal = invoice.getAdjustmentEntries().stream()
                .map(this::toSignedAdjustmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        invoice.setAdjustments(adjustmentTotal);
        invoice.setAdjustmentsAmount(adjustmentTotal);

        BigDecimal taxableSubtotal = invoice.getSubtotal().add(adjustmentTotal);
        if (taxableSubtotal.compareTo(BigDecimal.ZERO) < 0) {
            taxableSubtotal = BigDecimal.ZERO;
        }

        BigDecimal tax = taxServiceClient
                .calculateTax(taxableSubtotal, invoice.getPartyId())
                .setScale(4, RoundingMode.HALF_UP);
        invoice.setTax(tax);

        BigDecimal total = invoice.getSubtotal()
                .add(tax)
                .add(adjustmentTotal)
                .setScale(4, RoundingMode.HALF_UP);

        if (total.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "invoice total cannot be negative; adjustments would require a credit memo");
        }
        invoice.setTotal(total);
    }

    @NonNull
    private BigDecimal toSignedAdjustmentAmount(@NonNull InvoiceAdjustment adjustment) {
        BigDecimal amount = safeMoney(adjustment.getAmount(), BigDecimal.ZERO);
        return switch (adjustment.getType()) {
            case DISCOUNT -> amount.abs().negate();
            case FEE -> amount.abs();
            case CORRECTION -> amount;
        };
    }

    @NonNull
    private BigDecimal resolveLineTotal(@NonNull InvoiceLineItem sourceItem) {
        return sourceItem.getAmount().setScale(4, RoundingMode.HALF_UP);
    }

    @NonNull
    private BigDecimal safeMoney(@Nullable BigDecimal value, @NonNull BigDecimal fallback) {
        if (value == null) {
            return fallback.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    @NonNull
    private String generateInvoiceNumber(@NonNull Invoice invoice) {
        String idPart = invoice.getId() == null
                ? UUID.randomUUID().toString().substring(0, 8)
                : invoice.getId().toString().substring(0, 8);
        return "INV-" + Instant.now().toEpochMilli() + "-" + idPart;
    }

    @NonNull
    private InvoiceGenerationResponse toGenerationResponse(@NonNull Invoice invoice) {
        return InvoiceGenerationResponse.builder()
                .invoiceId(invoice.getId())
                .status(invoice.getStatus().name())
                .workorderId(invoice.getWorkorderId())
                .estimateId(invoice.getEstimateId())
                .approvalId(invoice.getApprovalId())
                .subtotal(invoice.getSubtotal())
                .taxAmount(invoice.getTax())
                .totalAmount(invoice.getTotal())
                .createdAt(invoice.getCreatedAt())
                .build();
    }

    @NonNull
    private InvoiceDetailsResponse toDetailsResponse(@NonNull Invoice invoice) {
        InvoiceDetailsResponse response = new InvoiceDetailsResponse();
        response.setInvoiceId(invoice.getId());
        response.setInvoiceNumber(invoice.getInvoiceNumber());
        response.setWorkorderId(invoice.getWorkorderId());
        response.setEstimateId(invoice.getEstimateId());
        response.setApprovalId(invoice.getApprovalId());
        response.setPartyId(invoice.getPartyId());
        response.setStatus(invoice.getStatus());
        response.setSubtotal(invoice.getSubtotal());
        response.setTax(invoice.getTax());
        response.setTotal(invoice.getTotal());
        response.setAdjustments(invoice.getAdjustments());
        response.setCreatedAt(invoice.getCreatedAt());
        response.setUpdatedAt(invoice.getUpdatedAt());
        response.setFinalizedAt(invoice.getFinalizedAt());
        response.setFinalizedBy(invoice.getFinalizedBy());

        List<InvoiceItemResponse> itemResponses = invoice.getItems().stream()
                .sorted(Comparator.comparing(item -> Objects.requireNonNullElse(item.getId(), UUID.randomUUID())))
                .map(this::toItemResponse)
                .toList();
        response.setItems(new ArrayList<>(itemResponses));

        List<InvoiceAdjustmentResponse> adjustmentResponses = invoice.getAdjustmentEntries().stream()
                .sorted(Comparator.comparing(InvoiceAdjustment::getCreatedAt))
                .map(this::toAdjustmentResponse)
                .toList();
        response.setAdjustmentEntries(new ArrayList<>(adjustmentResponses));

        return response;
    }

    @NonNull
    private InvoiceItemResponse toItemResponse(@NonNull InvoiceItem item) {
        InvoiceItemResponse response = new InvoiceItemResponse();
        response.setId(item.getId());
        response.setDescription(item.getDescription());
        response.setQuantity(item.getQuantity());
        response.setUnitPrice(item.getUnitPrice());
        response.setAmount(item.getLineTotal());
        response.setWorkorderItemId(item.getWorkorderItemId());
        return response;
    }

    @NonNull
    private InvoiceAdjustmentResponse toAdjustmentResponse(@NonNull InvoiceAdjustment adjustment) {
        InvoiceAdjustmentResponse response = new InvoiceAdjustmentResponse();
        response.setId(adjustment.getId());
        response.setType(adjustment.getType());
        response.setAmount(adjustment.getAmount());
        response.setReason(adjustment.getReason());
        response.setAuthorizedBy(adjustment.getAuthorizedBy());
        response.setCreatedAt(adjustment.getCreatedAt());
        return response;
    }
}
