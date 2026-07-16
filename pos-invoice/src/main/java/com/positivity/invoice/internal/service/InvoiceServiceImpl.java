package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.TaxServiceClient;
import com.positivity.invoice.internal.config.InvoiceEventPublisher;
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
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.InvoiceLineItem;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxLineItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InvoiceServiceImpl implements InvoiceService {
    private final Clock clock;

    private final InvoiceRepository invoiceRepository;
    private final TaxServiceClient taxServiceClient;
    private final LocationReferenceService locationReferenceService;
    private final WorkorderReferenceService workorderReferenceService;
    private final InvoiceEventPublisher invoiceEventPublisher;

    /**
     * Self-reference (lazy to break the construction cycle) used so the public
     * read/write entry points can invoke the {@code @Transactional} mapping
     * methods through the Spring proxy and then perform remote enrichment
     * outside the transaction boundary.
     */
    private InvoiceServiceImpl self;

    @Autowired
    public void setSelf(@Lazy InvoiceServiceImpl self) {
        this.self = self;
    }

    public InvoiceServiceImpl(
            @NonNull InvoiceRepository invoiceRepository,
            @NonNull TaxServiceClient taxServiceClient,
            @NonNull LocationReferenceService locationReferenceService,
            @NonNull WorkorderReferenceService workorderReferenceService,
            @NonNull InvoiceEventPublisher invoiceEventPublisher,
            Clock clock) {
        this.clock = clock;
        this.invoiceRepository = invoiceRepository;
        this.taxServiceClient = taxServiceClient;
        this.locationReferenceService = locationReferenceService;
        this.workorderReferenceService = workorderReferenceService;
        this.invoiceEventPublisher = invoiceEventPublisher;
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

        return invoiceRepository
                .findByWorkorderId(request.getWorkorderId())
                .map(this::toGenerationResponse)
                .orElseGet(() -> createNewInvoice(request));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @NonNull
    public InvoiceDetailsResponse getInvoice(@NonNull UUID invoiceId) {
        // Load + map inside a read-only transaction (via the proxy so lazy
        // collections initialize), then resolve the workorder number from the
        // remote workexec service OUTSIDE the transaction so the DB connection is
        // not held during the outbound call.
        InvoiceDetailsResponse response = self.loadInvoiceDetail(invoiceId);
        response.setWorkorderNumber(resolveWorkorderNumber(response.getWorkorderId()));
        return response;
    }

    @Transactional(readOnly = true)
    @NonNull
    public InvoiceDetailsResponse loadInvoiceDetail(@NonNull UUID invoiceId) {
        Invoice invoice =
                invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        return toDetailsResponse(invoice);
    }

    @Override
    @NonNull
    public InvoiceDetailsResponse applyAdjustment(@NonNull UUID invoiceId, @NonNull AdjustmentRequest request) {
        Invoice invoice =
                invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));

        validateDraftState(invoice);
        validateAdjustmentRequest(request);

        // Idempotent replay guard: a caller retrying after a transport timeout (its POST may
        // have committed here already) supplies the same externalReference — return the
        // existing adjustment instead of double-crediting (PaymentIntent.idempotencyKey
        // pattern; warranty settlements pass their settlement id).
        String externalReference = normalizeExternalReference(request.getExternalReference());
        if (externalReference != null) {
            boolean alreadyApplied = invoice.getAdjustmentEntries().stream()
                    .anyMatch(existing -> Objects.equals(existing.getType(), request.getType())
                            && externalReference.equals(existing.getExternalReference()));
            if (alreadyApplied) {
                InvoiceDetailsResponse existing = toDetailsResponse(invoice);
                existing.setWorkorderNumber(resolveWorkorderNumber(invoice.getWorkorderId()));
                return existing;
            }
        }

        InvoiceAdjustment adjustment = new InvoiceAdjustment();
        adjustment.setType(Objects.requireNonNull(request.getType(), "adjustment type is required"));
        adjustment.setAmount(request.getAmount().setScale(4, RoundingMode.HALF_UP));
        adjustment.setReason(request.getReason().trim());
        adjustment.setAuthorizedBy(request.getAuthorizedBy().trim());
        adjustment.setExternalReference(externalReference);

        invoice.addAdjustment(adjustment);
        recalculateTotals(invoice);

        Invoice saved = invoiceRepository.save(invoice);
        invoiceEventPublisher.publishInvoiceUpdated(saved);
        InvoiceDetailsResponse response = toDetailsResponse(saved);
        response.setWorkorderNumber(resolveWorkorderNumber(saved.getWorkorderId()));
        return response;
    }

    @NonNull
    private InvoiceGenerationResponse createNewInvoice(@NonNull InvoiceCreationRequest request) {
        Invoice invoice = new Invoice();
        invoice.setWorkorderId(request.getWorkorderId());
        invoice.setEstimateId(request.getEstimateId());
        invoice.setApprovalId(request.getApprovalId());
        invoice.setLocationId(request.getLocationId());
        // Canonical lowercase form (UUID.toString()) — party-scoped lookups
        // (InvoiceSearchServiceImpl.searchLinesByParty) match on the string column.
        invoice.setPartyId(
                request.getCustomerId() == null ? null : request.getCustomerId().toString());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setAdjustments(BigDecimal.ZERO);
        invoice.setAdjustmentsAmount(BigDecimal.ZERO);

        List<InvoiceLineItem> lineItems = request.getLineItems() == null ? List.of() : request.getLineItems();
        for (InvoiceLineItem sourceItem : lineItems) {
            InvoiceItem item = new InvoiceItem();
            String description = sourceItem.getDescription();
            item.setDescription(description.isBlank() ? "Invoice line item" : description.trim());
            item.setQuantity(safeMoney(sourceItem.getQuantity(), BigDecimal.ONE));
            item.setUnitPrice(safeMoney(sourceItem.getUnitPrice(), BigDecimal.ZERO));
            item.setLineTotal(resolveLineTotal(sourceItem));
            item.setType(sourceItem.getType());
            invoice.addItem(item);
        }

        BigDecimal subtotal = invoice.getItems().stream()
                .map(InvoiceItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        invoice.setSubtotal(subtotal);
        recalculateTotals(invoice);

        Invoice saved = invoiceRepository.save(invoice);
        // Assign the invoice number at draft creation. The number is the invoice's stable
        // identity for the whole of its lifecycle; deferring it left drafts showing a
        // placeholder that never resolved. Assigned after the first save so the generated
        // id is available for the number's id segment.
        if (saved.getInvoiceNumber() == null || saved.getInvoiceNumber().isBlank()) {
            saved.setInvoiceNumber(generateInvoiceNumber(saved));
            saved = invoiceRepository.save(saved);
        }
        invoiceEventPublisher.publishInvoiceUpdated(saved);
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

        BigDecimal tax = calculateTax(invoice);
        invoice.setTax(tax);

        BigDecimal total = invoice.getSubtotal().add(tax).add(adjustmentTotal).setScale(4, RoundingMode.HALF_UP);

        if (total.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "invoice total cannot be negative; adjustments would require a credit memo");
        }
        invoice.setTotal(total);
    }

    /**
     * Calculate tax for the invoice against its shop-location jurisdiction.
     *
     * <p>Tax is computed on the invoice line items (the goods/services sold), matching the
     * estimate flow. Invoice-level adjustments (discounts/fees) are applied to the total
     * after tax and are not part of the taxable base. When there is nothing taxable the tax
     * service is not called.
     */
    @NonNull
    private BigDecimal calculateTax(@NonNull Invoice invoice) {
        List<TaxLineItem> taxLineItems = buildTaxLineItems(invoice);
        if (taxLineItems.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        UUID locationId = invoice.getLocationId();
        if (locationId == null) {
            throw new IllegalStateException(
                    "invoice has taxable line items but no locationId to resolve the tax jurisdiction");
        }

        TaxAddress destination = locationReferenceService.resolveTaxAddress(locationId);
        return taxServiceClient
                .calculateTax(taxLineItems, destination, invoice.getWorkorderId())
                .setScale(4, RoundingMode.HALF_UP);
    }

    @NonNull
    private List<TaxLineItem> buildTaxLineItems(@NonNull Invoice invoice) {
        List<TaxLineItem> taxLineItems = new ArrayList<>();
        int index = 0;
        for (InvoiceItem item : invoice.getItems()) {
            BigDecimal quantity = safeMoney(item.getQuantity(), BigDecimal.ONE);
            BigDecimal unitPrice = safeMoney(item.getUnitPrice(), BigDecimal.ZERO);
            // pos-tax requires positive quantity and unit price; non-positive lines contribute
            // no tax, so skip them rather than tripping bean validation (400) on the request.
            if (quantity.signum() <= 0 || unitPrice.signum() <= 0) {
                continue;
            }
            taxLineItems.add(TaxLineItem.builder()
                    .lineItemId(String.valueOf(++index))
                    .description(item.getDescription())
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .taxExempt(false)
                    .build());
        }
        return taxLineItems;
    }

    @NonNull
    private BigDecimal toSignedAdjustmentAmount(@NonNull InvoiceAdjustment adjustment) {
        BigDecimal amount = safeMoney(adjustment.getAmount(), BigDecimal.ZERO);
        return switch (adjustment.getType()) {
            // A warranty credit reduces what the customer owes, exactly like a discount.
            case DISCOUNT, WARRANTY -> amount.abs().negate();
            case FEE -> amount.abs();
            case CORRECTION -> amount;
        };
    }

    /** Trim the optional external reference and collapse a blank value to {@code null}. */
    @Nullable
    private static String normalizeExternalReference(@Nullable String externalReference) {
        if (externalReference == null || externalReference.isBlank()) {
            return null;
        }
        return externalReference.trim();
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
                ? UUIDv7Generator.generate().toString().substring(0, 8)
                : invoice.getId().toString().substring(0, 8);
        return "INV-" + Instant.now(clock).toEpochMilli() + "-" + idPart;
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
        // workorderNumber is resolved by the caller outside the transaction.
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
        response.setRevertedAt(invoice.getRevertedAt());
        response.setReversionReason(invoice.getReversionReason());
        response.setRequiresManagerApproval(requiresManagerApproval(invoice));

        List<InvoiceItemResponse> itemResponses = invoice.getItems().stream()
                .sorted(Comparator.comparing(
                        item -> Objects.requireNonNullElse(item.getId(), UUIDv7Generator.generate())))
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
        response.setType(item.getType());
        return response;
    }

    /**
     * Resolves the human-readable workorder number for the detail view via the
     * workorder reference client (the same path the invoice search uses). Returns
     * {@code null} when the workorder is unknown or unresolved so the UI can fall
     * back to the identifier.
     */
    @Nullable
    private String resolveWorkorderNumber(@Nullable UUID workorderId) {
        if (workorderId == null) {
            return null;
        }
        return workorderReferenceService.resolveNumbers(Set.of(workorderId)).get(workorderId);
    }

    /**
     * Whether a DRAFT invoice exceeds the SERVICE_ADVISOR finalization threshold.
     *
     * <p>This flag mirrors the threshold portion of
     * {@link InvoiceFinalizationServiceImpl#checkEligibility} (role-agnostic): it
     * reflects whether the invoice total is over the limit, NOT the viewing
     * actor's authority. A SHOP_MANAGER is exempt from the approval code at
     * finalize time, but the detail response is computed without a role, so the UI
     * should treat this as "approval may be required" and rely on the finalize
     * endpoint for the authoritative enforcement.
     */
    private boolean requiresManagerApproval(@NonNull Invoice invoice) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            return false;
        }
        BigDecimal total = invoice.getTotal();
        return total != null && total.compareTo(InvoiceFinalizationServiceImpl.SERVICE_ADVISOR_LIMIT) > 0;
    }

    @NonNull
    private InvoiceAdjustmentResponse toAdjustmentResponse(@NonNull InvoiceAdjustment adjustment) {
        InvoiceAdjustmentResponse response = new InvoiceAdjustmentResponse();
        response.setId(adjustment.getId());
        response.setType(adjustment.getType());
        response.setAmount(adjustment.getAmount());
        response.setReason(adjustment.getReason());
        response.setAuthorizedBy(adjustment.getAuthorizedBy());
        response.setExternalReference(adjustment.getExternalReference());
        response.setCreatedAt(adjustment.getCreatedAt());
        return response;
    }
}
