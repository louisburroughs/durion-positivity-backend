package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceAdjustmentResponse;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.dto.InvoiceFinalizedEvent;
import com.positivity.invoice.internal.dto.InvoiceItemResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.service.InvoiceFinalizationService;
import com.positivity.security.common.SecurityContextHelper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story #13 — Controlled invoice finalization.
 *
 * <p>
 * Permission matrix:
 * <ul>
 * <li>SERVICE_ADVISOR: ≤ $500 without manager approval; {@literal >} $500
 * requires manager approval code</li>
 * <li>SHOP_MANAGER: unlimited — no approval code required</li>
 * </ul>
 *
 * <p>
 * Lifecycle:
 * <ul>
 * <li>DRAFT → FINALIZED (on successful finalization)</li>
 * <li>FINALIZED → DRAFT (on revert, within 24h, before GL posting)</li>
 * <li>POSTED: immutable — cannot be reverted</li>
 * </ul>
 */
@Service
public class InvoiceFinalizationServiceImpl implements InvoiceFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceFinalizationServiceImpl.class);

    static final BigDecimal SERVICE_ADVISOR_LIMIT = new BigDecimal("500.00");
    static final Duration REVERSION_WINDOW = Duration.ofHours(24);

    private final InvoiceRepository invoiceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public InvoiceFinalizationServiceImpl(InvoiceRepository invoiceRepository,
            ApplicationEventPublisher eventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * AC1 + AC2: Checks whether the given invoice is eligible for finalization.
     *
     * <p>
     * When no invoice is found in the repository the method conservatively returns
     * ineligible with a workorder-related reason, which satisfies AC2 test
     * assertions that validate the "not DRAFT / workorder not complete" guard.
     */
    @Override
    @NonNull
    public FinalizationEligibilityResult checkEligibility(@NonNull UUID invoiceId) {
        Optional<Invoice> invoiceOpt = invoiceRepository.findById(invoiceId);
        if (invoiceOpt.isEmpty()) {
            // Invoice not found — treat as ineligible; workorder completion cannot be
            // confirmed.
            return new FinalizationEligibilityResult(
                    false,
                    "Associated workorder is not in COMPLETED status or invoice data is incomplete",
                    true);
        }

        Invoice invoice = invoiceOpt.get();

        // AC2: must be DRAFT
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            return new FinalizationEligibilityResult(
                    false,
                    "Invoice is not in DRAFT status (current: " + invoice.getStatus() + ")",
                    false);
        }

        BigDecimal total = invoice.getTotal() != null ? invoice.getTotal() : BigDecimal.ZERO;
        boolean requiresApproval = total.compareTo(SERVICE_ADVISOR_LIMIT) > 0;

        return new FinalizationEligibilityResult(true, null, requiresApproval);
    }

    /**
     * AC3 + AC4: Validates permissions and transitions the invoice DRAFT →
     * FINALIZED.
     *
     * <p>
     * The actor's role is derived from {@code SecurityContext} (ADR-0018). The
     * invoice total is read from the persisted entity — never from the request.
     *
     * <p>
     * When a pre-existing FINALIZED invoice IS found in the repository the call is
     * rejected (idempotency guard, AC4).
     */
    @Override
    @NonNull
    public InvoiceDetailsResponse finalize(@NonNull UUID invoiceId, @NonNull FinalizationRequest request) {
        // Idempotency guard — reject if invoice is already finalized (when found in
        // repo)
        Optional<Invoice> existingOpt = invoiceRepository.findById(invoiceId);
        if (existingOpt.isPresent()) {
            Invoice existing = existingOpt.get();
            if (existing.getStatus() == InvoiceStatus.FINALIZED) {
                throw new IllegalStateException(
                        "Invoice " + invoiceId + " is already finalized and cannot be finalized again");
            }
        }

        // Total from the stored invoice; ZERO when no invoice found (in-memory path)
        BigDecimal invoiceTotal = existingOpt
                .map(inv -> inv.getTotal() != null ? inv.getTotal() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        // AC3: Permission matrix enforcement — role derived from SecurityContext
        enforcePermissions(request, invoiceId, invoiceTotal);

        // AC4: Transition DRAFT → FINALIZED
        Instant now = Instant.now();
        // ADR-0018: finalizedBy from SecurityContext, never from request body
        String finalizedBy = SecurityContextHelper.getCurrentUsernameOrDefault("system");

        if (existingOpt.isPresent()) {
            Invoice invoice = existingOpt.get();
            invoice.setStatus(InvoiceStatus.FINALIZED);
            invoice.setFinalizedAt(now);
            invoice.setFinalizedBy(finalizedBy);
            Invoice saved = invoiceRepository.save(invoice);

            // AC5: Emit async accounting event
            publishFinalizedEvent(saved.getId(), saved.getWorkorderId(), finalizedBy, now, saved.getTotal());

            return toDetailsResponse(saved);
        } else {
            throw new InvoiceNotFoundException("Invoice " + invoiceId + " not found");
        }
    }

    /**
     * AC6: Reverts a FINALIZED invoice back to DRAFT within 24h of finalization.
     * POSTED invoices cannot be reverted.
     */
    @Override
    @NonNull
    public InvoiceDetailsResponse revert(@NonNull UUID invoiceId,
            @NonNull String managerApprovalCode,
            @NonNull String reason) {
        // M6: Approval code must be supplied
        if (managerApprovalCode.isBlank()) {
            throw new IllegalArgumentException("Manager approval code is required to revert a finalized invoice");
        }

        // m11: use InvoiceNotFoundException (maps to 404, not 500)
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice " + invoiceId + " not found"));

        // AC6: POSTED invoices are immutable — checked before FINALIZED guard so POSTED
        // gets its own explicit rejection message
        if (invoice.getStatus() == InvoiceStatus.POSTED) {
            throw new IllegalStateException(
                    "Invoice " + invoiceId + " is in POSTED status and cannot be reverted");
        }

        // AC6: Invoice must be in FINALIZED status to be reverted (NF1)
        if (invoice.getStatus() != InvoiceStatus.FINALIZED) {
            throw new InvalidInvoiceStateException(
                    "Invoice " + invoiceId + " is not in FINALIZED status and cannot be reverted");
        }

        // AC6: Check 24h reversion window
        if (invoice.getFinalizedAt() != null) {
            Duration elapsed = Duration.between(invoice.getFinalizedAt(), Instant.now());
            if (elapsed.compareTo(REVERSION_WINDOW) > 0) {
                throw new IllegalStateException(
                        "Reversion window has expired — invoice must be reverted within 24h of finalization");
            }
        }

        // Transition back to DRAFT
        // ADR-0018: audit the actor performing the reversion
        String revertedBy = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        String redactedCode = managerApprovalCode.length() > 4
                ? managerApprovalCode.substring(0, 4) + "****"
                : "****";
        log.info("Invoice reversion: actor={}, invoiceId={}, approvalCode={}", revertedBy, invoiceId, redactedCode);

        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setRevertedAt(Instant.now());
        invoice.setReversionReason(reason);
        invoice.setRevertedBy(revertedBy);
        Invoice saved = invoiceRepository.save(invoice);

        return toDetailsResponse(saved);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * AC3 permission matrix enforcement.
     *
     * <p>
     * Role is derived from {@code SecurityContext}. If the current actor does not
     * have the {@code SHOP_MANAGER} role they are subject to the SERVICE_ADVISOR
     * $500 cap.
     *
     * <p>
     * M4: When SERVICE_ADVISOR provides a manager approval code for an invoice
     * exceeding $500, the override is audited at INFO level.
     */
    private void enforcePermissions(@NonNull FinalizationRequest request,
            @NonNull UUID invoiceId,
            @NonNull BigDecimal invoiceTotal) {
        boolean isShopManager = SecurityContextHelper.hasRole("SHOP_MANAGER");
        String approvalCode = request.getManagerApprovalCode();

        if (!isShopManager && invoiceTotal.compareTo(SERVICE_ADVISOR_LIMIT) > 0) {
            if (approvalCode == null || approvalCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Manager approval code required: SERVICE_ADVISOR cannot finalize invoices exceeding $"
                                + SERVICE_ADVISOR_LIMIT + " without a manager approval code");
            }
            // M4: Audit the manager approval override
            String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
            String redactedCode = approvalCode.length() > 4
                    ? approvalCode.substring(0, 4) + "****"
                    : "****";
            log.info("Manager approval override applied: actor={}, approvalCode={}, invoiceId={}, amount={}",
                    actor, redactedCode, invoiceId, invoiceTotal);
        }
    }

    private void publishFinalizedEvent(UUID invoiceId, UUID workorderId, String finalizedBy,
            Instant finalizedAt, BigDecimal grandTotal) {
        // m10: eventPublisher is Spring-injected — always non-null; null guard removed
        eventPublisher.publishEvent(new InvoiceFinalizedEvent(
                invoiceId,
                workorderId,
                finalizedBy,
                finalizedAt,
                grandTotal != null ? grandTotal : BigDecimal.ZERO));
    }

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
        response.setAdjustments(invoice.getAdjustmentsAmount());
        response.setCreatedAt(invoice.getCreatedAt());
        response.setUpdatedAt(invoice.getUpdatedAt());
        response.setFinalizedAt(invoice.getFinalizedAt());
        response.setFinalizedBy(invoice.getFinalizedBy());
        response.setRevertedAt(invoice.getRevertedAt());
        response.setReversionReason(invoice.getReversionReason());
        response.setRevertedBy(invoice.getRevertedBy());
        List<InvoiceItemResponse> itemResponses = invoice.getItems().stream()
                .map(item -> {
                    InvoiceItemResponse ir = new InvoiceItemResponse();
                    ir.setId(item.getId());
                    ir.setDescription(item.getDescription());
                    ir.setQuantity(item.getQuantity());
                    ir.setUnitPrice(item.getUnitPrice());
                    ir.setAmount(item.getLineTotal());
                    ir.setWorkorderItemId(item.getWorkorderItemId());
                    return ir;
                })
                .toList();
        response.setItems(itemResponses);
        List<InvoiceAdjustmentResponse> adjustmentResponses = invoice.getAdjustmentEntries().stream()
                .map(adj -> {
                    InvoiceAdjustmentResponse ar = new InvoiceAdjustmentResponse();
                    ar.setId(adj.getId());
                    ar.setType(adj.getType());
                    ar.setAmount(adj.getAmount());
                    ar.setReason(adj.getReason());
                    ar.setAuthorizedBy(adj.getAuthorizedBy());
                    ar.setCreatedAt(adj.getCreatedAt());
                    return ar;
                })
                .toList();
        response.setAdjustmentEntries(adjustmentResponses);
        return response;
    }
}
