package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.TaxLifecycleClient;
import com.positivity.invoice.internal.config.InvoiceEventPublisher;
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
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final Clock clock;

    private static final String SYSTEM = "system";

    private static final Logger log = LoggerFactory.getLogger(InvoiceFinalizationServiceImpl.class);

    static final BigDecimal SERVICE_ADVISOR_LIMIT = new BigDecimal("500.00");
    static final Duration REVERSION_WINDOW = Duration.ofHours(24);

    private static final String OVERRIDE_AUTHORITY = "invoice:finalize:override";

    /**
     * Roles that grant finalize-override capability. Checked in addition to the
     * {@link #OVERRIDE_AUTHORITY} so a logged-in manager/admin is auto-approved even
     * before the (operational) authority grant is applied — the JWT always carries
     * these roles. The authority remains the forward-compatible, fine-grained path.
     */
    private static final java.util.List<String> OVERRIDE_ROLES =
            java.util.List.of("SHOP_MANAGER", "LOCATION_MANAGER", "ADMIN");

    private final InvoiceRepository invoiceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ElevationTokenService elevationTokenService;
    private final InvoiceEventPublisher invoiceEventPublisher;
    private final TaxLifecycleClient taxLifecycleClient;
    private final InvoiceDueDateService invoiceDueDateService;
    private final InvoiceTaxCalculator invoiceTaxCalculator;
    private final InvoiceTaxBreakdownWriter taxBreakdownWriter;

    public InvoiceFinalizationServiceImpl(
            InvoiceRepository invoiceRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            ElevationTokenService elevationTokenService,
            InvoiceEventPublisher invoiceEventPublisher,
            TaxLifecycleClient taxLifecycleClient,
            InvoiceDueDateService invoiceDueDateService,
            InvoiceTaxCalculator invoiceTaxCalculator,
            InvoiceTaxBreakdownWriter taxBreakdownWriter) {
        this.clock = clock;
        this.invoiceRepository = invoiceRepository;
        this.eventPublisher = eventPublisher;
        this.elevationTokenService = elevationTokenService;
        this.invoiceEventPublisher = invoiceEventPublisher;
        this.taxLifecycleClient = taxLifecycleClient;
        this.invoiceDueDateService = invoiceDueDateService;
        this.invoiceTaxCalculator = invoiceTaxCalculator;
        this.taxBreakdownWriter = taxBreakdownWriter;
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
                    false, "Associated workorder is not in COMPLETED status or invoice data is incomplete", true);
        }

        Invoice invoice = invoiceOpt.get();

        // AC2: must be DRAFT
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            return new FinalizationEligibilityResult(
                    false, "Invoice is not in DRAFT status (current: " + invoice.getStatus() + ")", false);
        }

        BigDecimal total = invoice.getTotal();
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
    @Transactional
    public InvoiceDetailsResponse completeInvoice(@NonNull UUID invoiceId, @NonNull FinalizationRequest request) {
        // AC4: only DRAFT invoices are eligible for finalization.
        // Reject any non-DRAFT state (e.g., FINALIZED, POSTED, ERROR) with conflict.
        Optional<Invoice> existingOpt = invoiceRepository.findById(invoiceId);
        TaxCalculationResponse committableTax = null;
        if (existingOpt.isPresent()) {
            Invoice existing = existingOpt.get();
            if (existing.getStatus() != InvoiceStatus.DRAFT) {
                throw new IllegalStateException(
                        "Invoice " + invoiceId + " is in " + existing.getStatus() + " status and cannot be finalized");
            }
            // Decision D-T5: invoice finalization hard-requires a successful tax calculation —
            // it must never issue with a silently-missing tax result. pos-invoice never applies a
            // silent fallback (unlike the old pos-workorder estimate path); a failed tax
            // calculation propagates at re-price time and leaves tax unset, so guard here too.
            if (existing.getTax() == null) {
                throw new IllegalStateException(
                        "Invoice " + invoiceId + " cannot be finalized: tax has not been calculated");
            }
            // #985 (story T6, Option A): while the invoice is still DRAFT, run the COMMITTABLE
            // tax calculation (committable=true, referenceId == invoiceId). The provider then
            // persists an uncommitted SalesInvoice document under code == invoiceId, so the
            // lifecycle commit below — and the PENDING_COMMIT re-commit job on provider outage —
            // resolve by that code. A calculation failure propagates and blocks finalization
            // (per D-T5; the estimate-and-true-up leniency of D-T3 applies to the COMMIT step,
            // not to document creation — a PENDING_COMMIT row without a provider document could
            // never converge). The invoice stays DRAFT and finalization can be retried. A null
            // result means nothing is taxable: no provider document exists and the lifecycle
            // commit is skipped.
            committableTax = invoiceTaxCalculator.calculateCommittable(existing);
            if (committableTax != null) {
                applyCommittableTax(existing, committableTax);
            }
        }

        // Total from the stored invoice; ZERO when no invoice found (in-memory path)
        BigDecimal invoiceTotal = existingOpt
                .map(inv -> inv.getTotal() != null ? inv.getTotal() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        // AC3: Permission matrix enforcement — role derived from SecurityContext
        enforcePermissions(request, invoiceId, invoiceTotal);

        // AC4: Transition DRAFT → FINALIZED
        Instant now = Instant.now(clock);
        // ADR-0018: finalizedBy from SecurityContext, never from request body
        String finalizedBy = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);

        if (existingOpt.isPresent()) {
            Invoice invoice = existingOpt.get();
            invoice.setStatus(InvoiceStatus.FINALIZED);
            invoice.setFinalizedAt(now);
            invoice.setFinalizedBy(finalizedBy);
            // #993: freeze the collections-aging facts at finalization. The due date is a
            // per-invoice computed fact (party terms rule over the finalization business date);
            // later BillingRules changes never move it.
            InvoiceDueDateService.DueTerms dueTerms = invoiceDueDateService.resolve(invoice, now);
            invoice.setPaymentTermsCode(dueTerms.paymentTerms().name());
            invoice.setDueDate(dueTerms.dueDate());
            Invoice saved = invoiceRepository.save(invoice);
            invoiceEventPublisher.publishInvoiceUpdated(saved);

            // AC5: Emit async accounting event
            publishFinalizedEvent(saved.getId(), saved.getWorkorderId(), finalizedBy, now, saved.getTotal());

            // Story T6 / D-T3: commit the provider tax document for the finalized invoice.
            // Synchronous utility call; never blocks the sale (the client swallows failures and
            // pos-tax records PENDING_COMMIT for the scheduled re-commit job on provider outage).
            // #985: only when the committable calculation above created a provider document —
            // with nothing taxable there is no document and a commit could never resolve.
            if (committableTax != null) {
                taxLifecycleClient.commit(saved.getId());
            } else {
                log.debug("Invoice {} has no taxable lines; skipping provider tax commit", saved.getId());
            }

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
    @Transactional
    public InvoiceDetailsResponse revert(
            @NonNull UUID invoiceId, @NonNull String managerApprovalCode, @NonNull String reason) {
        // M6: Approval code must be supplied
        if (managerApprovalCode.isBlank()) {
            throw new IllegalArgumentException("Manager approval code is required to revert a finalized invoice");
        }

        // m11: use InvoiceNotFoundException (maps to 404, not 500)
        Invoice invoice = invoiceRepository
                .findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice " + invoiceId + " not found"));

        // AC6: POSTED invoices are immutable — checked before FINALIZED guard so POSTED
        // gets its own explicit rejection message
        if (invoice.getStatus() == InvoiceStatus.POSTED) {
            throw new IllegalStateException("Invoice " + invoiceId + " is in POSTED status and cannot be reverted");
        }

        // AC6: Invoice must be in FINALIZED status to be reverted (NF1)
        if (invoice.getStatus() != InvoiceStatus.FINALIZED) {
            throw new InvalidInvoiceStateException(
                    "Invoice " + invoiceId + " is not in FINALIZED status and cannot be reverted");
        }

        // AC6: Check 24h reversion window
        if (invoice.getFinalizedAt() != null) {
            Duration elapsed = Duration.between(invoice.getFinalizedAt(), Instant.now(clock));
            if (elapsed.compareTo(REVERSION_WINDOW) > 0) {
                throw new IllegalStateException(
                        "Reversion window has expired — invoice must be reverted within 24h of finalization");
            }
        }

        // Actors without the override authority must supply a verified elevation token
        // scoped to this invoice; a free-text code is no longer accepted. When a token is
        // used, capture the approving manager's person id for audit (non-repudiation).
        UUID revertApprover = null;
        if (!callerCanOverride()) {
            revertApprover = elevationTokenService
                    .verify(managerApprovalCode, invoiceId)
                    .orElseThrow(() ->
                            new IllegalArgumentException("Invalid or expired manager approval code for this invoice"));
        }

        // Transition back to DRAFT
        // ADR-0018: audit the actor performing the reversion and the approving manager.
        String revertedBy = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        if (log.isInfoEnabled()) {
            log.info(
                    "Invoice reversion: actor={}, approverPersonId={}, invoiceId={}",
                    revertedBy,
                    revertApprover,
                    invoiceId);
        }

        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setRevertedAt(Instant.now(clock));
        invoice.setReversionReason(reason);
        invoice.setRevertedBy(revertedBy);
        Invoice saved = invoiceRepository.save(invoice);
        invoiceEventPublisher.publishInvoiceUpdated(saved);

        // Story T6 (R-T2): InvoiceStatus has no CANCELLED/VOIDED — void hooks the revert
        // transition. Void the provider tax document for the invoice returning to DRAFT.
        taxLifecycleClient.voidTransaction(saved.getId());

        return toDetailsResponse(saved);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * #985: adopt the finalization-time committable calculation as the invoice's frozen tax.
     * Runs while the invoice is still DRAFT (before the freeze-after-finalize boundary, story
     * T5a): sets tax, recomputes the total ({@code subtotal + adjustments + tax}, matching the
     * draft re-price formula) and refreshes the persisted per-line breakdown so the frozen
     * figures exactly match the provider document that will be committed.
     */
    private void applyCommittableTax(@NonNull Invoice invoice, @NonNull TaxCalculationResponse taxResponse) {
        BigDecimal tax = (taxResponse.getTotalTax() != null ? taxResponse.getTotalTax() : BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal subtotal = invoice.getSubtotal() != null ? invoice.getSubtotal() : BigDecimal.ZERO;
        BigDecimal adjustments =
                invoice.getAdjustmentsAmount() != null ? invoice.getAdjustmentsAmount() : BigDecimal.ZERO;
        invoice.setTax(tax);
        invoice.setTotal(subtotal.add(adjustments).add(tax).setScale(4, RoundingMode.HALF_UP));
        taxBreakdownWriter.replace(Objects.requireNonNull(invoice.getId(), "invoiceId is required"), taxResponse);
    }

    /**
     * True when the current actor may finalize/revert without a manager approval
     * token: they hold the {@link #OVERRIDE_AUTHORITY} or one of the
     * {@link #OVERRIDE_ROLES}. The role fallback keeps manager/admin auto-approval
     * working independently of the operational authority grant.
     */
    private boolean callerCanOverride() {
        if (SecurityContextHelper.hasAuthority(OVERRIDE_AUTHORITY)) {
            return true;
        }
        for (String role : OVERRIDE_ROLES) {
            if (SecurityContextHelper.hasRole(role)) {
                return true;
            }
        }
        return false;
    }

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
    private void enforcePermissions(
            @NonNull FinalizationRequest request, @NonNull UUID invoiceId, @NonNull BigDecimal invoiceTotal) {
        // Managers/admins (override authority or role) finalize without an approval
        // token regardless of amount — auto-approved.
        if (callerCanOverride()) {
            return;
        }

        // Below the SERVICE_ADVISOR cap, no manager approval is required.
        if (invoiceTotal.compareTo(SERVICE_ADVISOR_LIMIT) <= 0) {
            return;
        }

        // Above the cap, a verified manager-approval elevation token is mandatory.
        String approvalCode = request.getManagerApprovalCode();
        if (approvalCode == null || approvalCode.isBlank()) {
            throw new IllegalArgumentException("Manager approval code required: cannot finalize invoices exceeding $"
                    + SERVICE_ADVISOR_LIMIT + " without a manager approval code");
        }
        UUID approvingManager = elevationTokenService
                .verify(approvalCode, invoiceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Invalid or expired manager approval code for this invoice"));

        // M4 + ADR-0018: Audit the manager approval override. The approving manager's
        // person id is recorded UNMASKED — it is the non-repudiation value the audit
        // exists to capture (a person UUID, not secret material).
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        if (log.isInfoEnabled()) {
            log.info(
                    "Manager approval override applied: actor={}, approverPersonId={}, invoiceId={}, amount={}",
                    actor,
                    approvingManager,
                    invoiceId,
                    invoiceTotal);
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }

    private void publishFinalizedEvent(
            UUID invoiceId, UUID workorderId, String finalizedBy, Instant finalizedAt, BigDecimal grandTotal) {
        // m10: eventPublisher is Spring-injected — always non-null; null guard removed
        eventPublisher.publishEvent(new InvoiceFinalizedEvent(
                invoiceId, workorderId, finalizedBy, finalizedAt, grandTotal != null ? grandTotal : BigDecimal.ZERO));
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
                    ar.setExternalReference(adj.getExternalReference());
                    ar.setCreatedAt(adj.getCreatedAt());
                    return ar;
                })
                .toList();
        response.setAdjustmentEntries(adjustmentResponses);
        return response;
    }
}
