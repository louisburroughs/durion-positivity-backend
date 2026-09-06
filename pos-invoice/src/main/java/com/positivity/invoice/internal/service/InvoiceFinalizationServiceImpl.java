package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.TaxLifecycleClient;
import com.positivity.invoice.internal.config.InvoiceEventPublisher;
import com.positivity.invoice.internal.dto.FinalizationEligibilityResult;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceAdjustmentResponse;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.dto.InvoiceItemResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvalidManagerApprovalException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.ManagerApprovalRequiredException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <li>Holders of {@code invoice:finalize:override}: unlimited — no approval
 * code required</li>
 * </ul>
 *
 * <p>
 * Lifecycle:
 * <ul>
 * <li>DRAFT → FINALIZED (on successful finalization). Finalization emits
 * {@code invoice.invoice.updated} (status FINALIZED) through the transactional outbox;
 * pos-accounting consumes that fact and posts the revenue journal entry (ADR-0044 §6,
 * #1843).</li>
 * <li>FINALIZED → POSTED when pos-accounting's {@code accounting.invoice.gl-posted} fact
 * arrives on {@code accounting.events.v1} — see {@link AccountingEventsListener} and
 * {@link #markPosted(UUID, UUID, Instant)}. The journal entry id is recorded as
 * {@code glEntryId}. pos-invoice never fabricates a GL entry itself.</li>
 * <li>FINALIZED → DRAFT (on revert, within 24h, before the GL-posted fact arrives). A revert
 * that races the posting is reconciled by pos-accounting reversing the entry when it sees
 * the DRAFT fact; the late {@code gl-posted} fact is then skipped here.</li>
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

    private final InvoiceRepository invoiceRepository;
    private final ElevationTokenService elevationTokenService;
    private final InvoiceEventPublisher invoiceEventPublisher;
    private final TaxLifecycleClient taxLifecycleClient;
    private final InvoiceDueDateService invoiceDueDateService;
    private final InvoiceTaxCalculator invoiceTaxCalculator;
    private final InvoiceTaxBreakdownWriter taxBreakdownWriter;

    public InvoiceFinalizationServiceImpl(
            InvoiceRepository invoiceRepository,
            Clock clock,
            ElevationTokenService elevationTokenService,
            InvoiceEventPublisher invoiceEventPublisher,
            TaxLifecycleClient taxLifecycleClient,
            InvoiceDueDateService invoiceDueDateService,
            InvoiceTaxCalculator invoiceTaxCalculator,
            InvoiceTaxBreakdownWriter taxBreakdownWriter) {
        this.clock = clock;
        this.invoiceRepository = invoiceRepository;
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
        }

        // Total from the stored invoice; ZERO when no invoice found (in-memory path)
        BigDecimal invoiceTotal = existingOpt
                .map(inv -> inv.getTotal() != null ? inv.getTotal() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        // AC3: Permission matrix enforcement — role derived from SecurityContext.
        // PR #1020 review: enforced BEFORE the committable tax calculation below so an
        // unauthorized/unapproved request never triggers the provider-persisting call
        // (no orphan AvaTax document from a rejected finalization). The manager-approval
        // matrix therefore gates on the STORED total as it stands when finalization is
        // requested (the last draft re-price), not on the recalculated one.
        enforcePermissions(request, invoiceId, invoiceTotal);

        TaxCalculationResponse committableTax = null;
        if (existingOpt.isPresent()) {
            Invoice existing = existingOpt.get();
            if (existing.getDepositSourceType() != null) {
                // #1629: a deposit-take invoice is a contract-liability/cash-receipt document,
                // not a taxable sale — tax is recognized on the settlement invoice only. Skip
                // the committable tax calculation entirely (no provider document is created for
                // it, so there is nothing to commit or re-commit either); freeze tax at ZERO and
                // clear any stale breakdown via the same null-response path applyCommittableTax
                // already uses for "nothing taxable".
                applyCommittableTax(invoiceId, existing, null);
            } else {
                // #985 (story T6, Option A): while the invoice is still DRAFT, run the
                // COMMITTABLE tax calculation (committable=true, referenceId == invoiceId). The
                // provider then persists an uncommitted SalesInvoice document under code ==
                // invoiceId, so the lifecycle commit below — and the PENDING_COMMIT re-commit
                // job on provider outage — resolve by that code. A calculation failure
                // propagates and blocks finalization (per D-T5; the estimate-and-true-up
                // leniency of D-T3 applies to the COMMIT step, not to document creation — a
                // PENDING_COMMIT row without a provider document could never converge). The
                // invoice stays DRAFT and finalization can be retried. A null result means
                // nothing is taxable: no provider document exists, the lifecycle commit is
                // skipped, and any previously frozen tax is zeroed (stale-tax guard).
                committableTax = invoiceTaxCalculator.calculateCommittable(existing);
                applyCommittableTax(invoiceId, existing, committableTax);
            }
        }

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
            // AC5 / #1843: this FINALIZED fact is what drives GL posting — pos-accounting
            // consumes it, posts the revenue entry, and answers with accounting.invoice.gl-posted
            // (see markPosted). No in-process accounting event is emitted any more.
            invoiceEventPublisher.publishInvoiceUpdated(saved);

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
        // M6: Approval code must be supplied.
        // #1694 (d): defensive invariant — RevertRequest.managerApprovalCode carries @NotBlank,
        // so a blank code is already rejected by bean validation (400) before this method runs;
        // left as a bare IllegalArgumentException for any future direct caller.
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
            // (b) ADR-0017 §2: a supplied token that fails verification is a domain-policy
            // rejection, not a malformed request (#1694).
            revertApprover = elevationTokenService
                    .verify(managerApprovalCode, invoiceId)
                    .orElseThrow(() -> new InvalidManagerApprovalException(
                            "Invalid or expired manager approval code for this invoice"));
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

    /**
     * #1843: applies pos-accounting's {@code accounting.invoice.gl-posted} (POSTED) fact.
     *
     * <p>
     * The fact carries the {@code finalizedAt} of the finalization instance the journal entry
     * was posted for, so a fact belonging to an earlier finalize/revert cycle never promotes a
     * re-finalized invoice. Outcomes:
     * <ul>
     * <li>FINALIZED with a matching {@code finalizedAt}: transition to POSTED, record the
     * journal entry id as {@code glEntryId}, and emit {@code invoice.invoice.updated} so
     * downstream replicas see POSTED.</li>
     * <li>Already POSTED with the same {@code glEntryId}: duplicate delivery, no-op.</li>
     * <li>Any other state — POSTED under a different entry, DRAFT/CANCELLED (reverted or
     * cancelled in the race window; accounting reverses the entry when it sees that fact), a
     * different {@code finalizedAt}, or an unknown invoice — is logged at WARN and skipped.
     * Facts are never rejected: skipping is the reconciliation, not an error.</li>
     * </ul>
     */
    @Override
    @Transactional
    public void markPosted(@NonNull UUID invoiceId, @NonNull UUID glEntryId, @NonNull Instant finalizedAt) {
        Optional<Invoice> invoiceOpt = invoiceRepository.findById(invoiceId);
        if (invoiceOpt.isEmpty()) {
            log.warn(
                    "GL-posted fact skipped — invoice not found: invoiceId={}, glEntryId(mask)={}",
                    invoiceId,
                    maskForLog(glEntryId));
            return;
        }
        Invoice invoice = invoiceOpt.get();

        if (invoice.getStatus() == InvoiceStatus.POSTED) {
            if (glEntryId.equals(invoice.getGlEntryId())) {
                log.debug("GL-posted fact skipped — already POSTED under the same entry: invoiceId={}", invoiceId);
            } else {
                log.warn(
                        "GL-posted fact skipped — invoice already POSTED under a different entry: invoiceId={}, "
                                + "glEntryId(mask)={}, factGlEntryId(mask)={}",
                        invoiceId,
                        maskForLog(invoice.getGlEntryId()),
                        maskForLog(glEntryId));
            }
            return;
        }

        if (invoice.getStatus() != InvoiceStatus.FINALIZED) {
            log.warn(
                    "GL-posted fact skipped — invoice is {} (reverted or cancelled before posting; accounting "
                            + "reverses on that fact): invoiceId={}, glEntryId(mask)={}",
                    invoice.getStatus(),
                    invoiceId,
                    maskForLog(glEntryId));
            return;
        }

        if (invoice.getFinalizedAt() == null) {
            log.warn(
                    "GL-posted fact applied to a FINALIZED invoice with no finalizedAt — cannot verify the "
                            + "finalization instance: invoiceId={}, factFinalizedAt={}",
                    invoiceId,
                    finalizedAt);
        } else if (!sameInstant(invoice.getFinalizedAt(), finalizedAt)) {
            log.warn(
                    "GL-posted fact skipped — finalization instance mismatch: invoiceId={}, finalizedAt={}, "
                            + "factFinalizedAt={}",
                    invoiceId,
                    invoice.getFinalizedAt(),
                    finalizedAt);
            return;
        }

        invoice.setStatus(InvoiceStatus.POSTED);
        invoice.setGlEntryId(glEntryId);
        Invoice saved = invoiceRepository.save(invoice);
        invoiceEventPublisher.publishInvoiceUpdated(saved);
        if (log.isInfoEnabled()) {
            log.info(
                    "Invoice POSTED from GL-posted fact: invoiceId={}, glEntryId(mask)={}",
                    invoiceId,
                    maskForLog(glEntryId));
        }
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
     *
     * <p>A {@code null} response means nothing is taxable at finalization time: the frozen tax
     * is ZERO and the persisted breakdown is cleared — a previously computed (now stale) draft
     * tax must not survive into the FINALIZED invoice when no provider document exists. Also
     * invoked directly with {@code null} for deposit-take invoices (#1629), which never run the
     * committable calculation at all — same zero-and-clear result.
     */
    private void applyCommittableTax(
            @NonNull UUID invoiceId, @NonNull Invoice invoice, @Nullable TaxCalculationResponse taxResponse) {
        BigDecimal tax = (taxResponse != null && taxResponse.getTotalTax() != null
                        ? taxResponse.getTotalTax()
                        : BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal subtotal = invoice.getSubtotal() != null ? invoice.getSubtotal() : BigDecimal.ZERO;
        BigDecimal adjustments =
                invoice.getAdjustmentsAmount() != null ? invoice.getAdjustmentsAmount() : BigDecimal.ZERO;
        invoice.setTax(tax);
        invoice.setTotal(subtotal.add(adjustments).add(tax).setScale(4, RoundingMode.HALF_UP));
        taxBreakdownWriter.replace(invoiceId, taxResponse);
    }

    /**
     * True when the current actor may finalize/revert without a manager approval
     * token, i.e. they hold {@link #OVERRIDE_AUTHORITY}.
     *
     * <p>
     * This used to also accept the bare role names SHOP_MANAGER, LOCATION_MANAGER
     * and ADMIN, as a bridge for the window in which the authority existed but
     * nothing granted it. That window is closed: #1374 seeded
     * {@code invoice:finalize:override} to ADMIN and every manager role
     * (ACCOUNT_MANAGER, GENERAL_MANAGER, LOCATION_MANAGER, MANAGER, SHOP_MANAGER),
     * so the permission is a strict superset of the roles the fallback covered and
     * #1373 retired it. Override capability is now decided by one grant in
     * {@code role_permissions} rather than by two rules that could disagree.
     */
    private boolean callerCanOverride() {
        return SecurityContextHelper.hasAuthority(OVERRIDE_AUTHORITY);
    }

    /**
     * AC3 permission matrix enforcement.
     *
     * <p>
     * Authority is derived from {@code SecurityContext}. If the current actor does
     * not hold {@code invoice:finalize:override} they are subject to the
     * SERVICE_ADVISOR $500 cap.
     *
     * <p>
     * M4: When SERVICE_ADVISOR provides a manager approval code for an invoice
     * exceeding $500, the override is audited at INFO level.
     */
    private void enforcePermissions(
            @NonNull FinalizationRequest request, @NonNull UUID invoiceId, @NonNull BigDecimal invoiceTotal) {
        // Holders of the override authority finalize without an approval token
        // regardless of amount — auto-approved.
        if (callerCanOverride()) {
            return;
        }

        // Below the SERVICE_ADVISOR cap, no manager approval is required.
        if (invoiceTotal.compareTo(SERVICE_ADVISOR_LIMIT) <= 0) {
            return;
        }

        // Above the cap, a verified manager-approval elevation token is mandatory. (b) ADR-0017
        // §2: FinalizationRequest.managerApprovalCode is optional at the DTO level — it becomes
        // required only by this amount-conditioned domain policy, not by request shape (#1694).
        String approvalCode = request.getManagerApprovalCode();
        if (approvalCode == null || approvalCode.isBlank()) {
            throw new ManagerApprovalRequiredException(
                    "Manager approval code required: cannot finalize invoices exceeding $" + SERVICE_ADVISOR_LIMIT
                            + " without a manager approval code");
        }
        UUID approvingManager = elevationTokenService
                .verify(approvalCode, invoiceId)
                .orElseThrow(() -> new InvalidManagerApprovalException(
                        "Invalid or expired manager approval code for this invoice"));

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

    /**
     * Tolerance for matching the fact's {@code finalizedAt} against the stored one. The stored
     * value round-trips through the database at microsecond precision (rounded, so it can land on
     * the far side of a millisecond boundary) while the fact echoes the value the outbox
     * serialized from the in-memory entity; comparing by distance rather than by truncation keeps
     * boundary rounding from rejecting a legitimate fact. Two finalization instances of one
     * invoice can never be a millisecond apart (revert needs manager approval in between).
     */
    static final Duration FINALIZED_AT_TOLERANCE = Duration.ofMillis(1);

    private static boolean sameInstant(@NonNull Instant stored, @NonNull Instant fromFact) {
        return Duration.between(stored, fromFact).abs().compareTo(FINALIZED_AT_TOLERANCE) <= 0;
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
