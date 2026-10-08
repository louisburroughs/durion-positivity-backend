package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidBillAllocationException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.payment.GatewayPaymentRequest;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of AP Payment orchestration service.
 *
 * @see APPaymentService
 */
@Service
@RequiredArgsConstructor
public class APPaymentServiceImpl implements APPaymentService {
    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(APPaymentServiceImpl.class);
    private static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("00000000-0000-4000-a000-000000000010");

    private final APPaymentRepository paymentRepository;
    private final APPaymentAllocationRepository allocationRepository;
    private final VendorBillRepository billRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PaymentGatewayProvider paymentGateway;
    private final OutboxService outboxService;
    private final APPaymentFailurePersistenceService paymentFailurePersistenceService;
    private final VendorBillPayGuard payGuard;
    private final APPaymentPreGatewayChecks preGatewayChecks;
    private final APPaymentPostingService postingService;
    private final ApLockTimeout lockTimeout;
    private final SupplierVendorCopies vendorCopies;

    /** One allocation of the plan: the bill, locked, and the amount applied to it. */
    record PlannedAllocation(
            @NonNull VendorBill bill, @NonNull BigDecimal appliedAmount) {}

    @Override
    @Transactional
    @SuppressWarnings({"java:S1181", "java:S2221"}) // Catching Exception is intentional for gateway-level
    // failures
    public @NonNull APPaymentResponse executePayment(
            @NonNull ExecuteAPPaymentRequest request, @NonNull String currentUser) {
        // Check idempotency: if paymentRef exists, validate payload match and return
        // existing
        Optional<APPayment> existingPayment = paymentRepository.findByPaymentRef(request.getPaymentRef());
        if (existingPayment.isPresent()) {
            APPayment existing = existingPayment.get();
            validateIdempotency(existing, request);
            return toResponse(existing);
        }

        // ---- The pre-gateway block (CAP:550 S13, #2510; ruling 6048398147 item 4; S42, #2603) ----------------------
        // Every check that can refuse the payment runs here, in this order, before the payment row is saved and
        // before the gateway is called; a refusal charges nothing and saves no payment, so the same paymentRef may be
        // sent again (a refusal's own audit row, e.g. VENDOR_BILL_PAYMENT_REFUSED, commits in its own transaction). The
        // first refusal wins. Guard order (S42 ruling 2 of #2603; whichever of S24/S42 merges second keeps it):
        //   1. the request checks (APPaymentPreGatewayChecks#checkRequest):
        //        1a. method: CREDIT_CARD, OTHER -> 422 AP_PAYMENT_METHOD_NOT_SUPPORTED (S42, OI-17);
        //        1b. currency: not the functional currency -> 422 CURRENCY_NOT_SUPPORTED (S42, ADR-0067 PC-9 (a));
        //        1c. bank account: missing and not exactly one eligible, or not eligible -> 400 VALIDATION_ERROR
        //            fieldErrors[bankAccountId] (S42, AW41);
        //        1d. the vendor: not in the copy -> 422 VENDOR_NOT_FOUND, INACTIVE -> 422 VENDOR_INACTIVE (S24, AW23;
        //            an inactive vendor's existing bills are not paid either, ruling 3 of #2517);
        //   2. the allocation plan, its bills locked in id order (explicit, or oldest due first; S13);
        //   3. the pay guard, approver is not payer -> 403 AP_PAYMENT_SELF_APPROVED_BILL (S13);
        //   4. the remit-to check: a planned bill approved at another remit-to version than the copy's current one,
        //      unless someone other than the payer confirmed it -> 409 VENDOR_PAYMENT_DETAILS_CHANGED (S24, rule 6);
        //   5. the period and mapping checks (APPaymentPreGatewayChecks#checkPeriodAndMapping, S42):
        //        5a. time zone -> 422 ACCOUNTING_TIME_ZONE_UNSET; 5b. hard lock -> 422 PERIOD_HARD_LOCKED;
        //        5c. closed period without an accepted override -> 422 PERIOD_CLOSED (the period row read unlocked: no
        //            period lock is held across the gateway call; a period closed meanwhile refuses the outbox posting,
        //            and the payment goes GL_POST_FAILED);
        //        5d. AP_PAYMENT/ACCOUNTS_PAYABLE, and PAYMENT_FEES when fee > 0 -> 422 GL_MAPPING_NOT_CONFIGURED.
        // A refused allocation (a bill missing, not APPROVED or another vendor's; over-allocation) is refused in
        // slot 2 (ruling 6063520413 item 4).
        // Bounded waits (#2627): the bill locks of slot 2 (FOR UPDATE; the automatic plan locks every APPROVED bill of
        // the vendor) are held until this transaction ends, across the gateway call below; the period row is not
        // locked. accounting.ap.lock-timeout (SET LOCAL lock_timeout, default 5 s) bounds every wait for them
        // (409 LOCK_TIMEOUT, nothing persisted), and the gateway's own connect and read timeouts (5 s / 20 s) bound
        // how long they are held.
        lockTimeout.apply();
        Optional<LocalDate> businessDate = preGatewayChecks.businessDate();
        UUID bankAccountId = preGatewayChecks.checkRequest(request, businessDate); // slot 1, 1a-1c
        ExtSupplierVendor vendor = vendorCopies.requireForNewBusiness(request.getVendorId(), "A payment"); // 1d
        List<PlannedAllocation> plan = plan(request); // slot 2
        List<VendorBill> plannedBills =
                plan.stream().map(PlannedAllocation::bill).toList();
        payGuard.check(plannedBills, currentUser, request.getPaymentRef()); // slot 3
        vendorCopies.requireRemitToUnchanged(plannedBills, vendor, currentUser); // slot 4
        APPaymentPreGatewayChecks.Execution execution = preGatewayChecks.checkPeriodAndMapping(
                businessDate, request.getFeeAmount(), request.getOverrideJustification()); // slot 5
        // ---- end of the pre-gateway block -------------------------------------------------------------------

        // Create payment entity
        APPayment payment = new APPayment();
        payment.setPaymentRef(request.getPaymentRef());
        payment.setVendorId(request.getVendorId());
        payment.setVendorName(vendor.getDisplayName());
        payment.setGrossAmount(request.getGrossAmount());
        payment.setFeeAmount(request.getFeeAmount());
        payment.setCurrency(request.getCurrency().trim().toUpperCase(Locale.ROOT));
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setMemo(request.getMemo());
        payment.setBankAccountId(bankAccountId);
        payment.setPaymentDate(execution.date());
        if (execution.overrideAccepted()) {
            // The override travels with the payment (ruling 5 of #2603): its posting applies it as the payer.
            payment.setPeriodOverrideJustification(request.getOverrideJustification());
            payment.setPeriodOverrideBy(currentUser);
        }
        payment.setStatus(APPaymentStatus.INITIATED);
        payment.setCreatedBy(currentUser);

        // Execute payment through gateway with idempotency
        payment.setStatus(APPaymentStatus.GATEWAY_PENDING);
        payment = paymentRepository.save(payment);

        try {
            String paymentSource = request.getPaymentSource();
            if (paymentSource == null || paymentSource.isBlank()) {
                paymentSource = "UNSPECIFIED";
            }
            // Call payment gateway with idempotency key
            GatewayPaymentRequest gatewayRequest = GatewayPaymentRequest.builder()
                    .idempotencyKey(request.getPaymentRef()) // Use paymentRef as idempotency key
                    .amount(request.getGrossAmount())
                    .currency(request.getCurrency())
                    .paymentMethod(request.getPaymentMethod())
                    .vendorId(request.getVendorId().toString())
                    .paymentSource(paymentSource)
                    .memo(request.getMemo())
                    .metadata(java.util.List.of("ap_payment")) // metadata tags
                    .build();

            GatewayPaymentResponse gatewayResponse = paymentGateway.executePayment(gatewayRequest);

            // Capture gateway response
            payment.setGatewayTransactionId(gatewayResponse.getTransactionId());
            payment.setGatewayTimestamp(Instant.now(clock));
            payment.setGatewayResponse(gatewayResponse.getRawResponse());

            // Map gateway status to payment status
            switch (gatewayResponse.getStatus()) {
                case SUCCEEDED, AUTHORIZED -> payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
                case PENDING -> payment.setStatus(APPaymentStatus.GATEWAY_PENDING);
                case DECLINED, FAILED -> {
                    payment.setStatus(APPaymentStatus.GATEWAY_FAILED);
                    payment.setGatewayResponse("Gateway declined: "
                            + (gatewayResponse.getFailureReason() != null
                                    ? gatewayResponse.getFailureReason()
                                    : "Unknown reason"));
                    throw new PaymentGatewayException(
                            "Payment declined by gateway: " + gatewayResponse.getFailureReason());
                }
            }

            payment = paymentRepository.save(payment);

            // Apply the allocations the plan decided before the gateway call
            applyAllocations(payment, plan);

            // Persist event to outbox for at-least-once delivery guarantee
            payment.setStatus(APPaymentStatus.GL_POST_PENDING);
            payment = paymentRepository.save(payment);

            List<APPaymentAllocation> savedAllocations =
                    allocationRepository.findByPayment_PaymentIdOrderByAllocationSequenceAsc(payment.getPaymentId());

            APPaymentGLPostingEvent glPostingEvent = APPaymentGLPostingEvent.builder()
                    .eventId(UUIDv7Generator.generate())
                    .organizationId(DEFAULT_ORGANIZATION_ID)
                    .paymentId(payment.getPaymentId())
                    .paymentRef(payment.getPaymentRef())
                    .vendorId(payment.getVendorId())
                    .vendorName(payment.getVendorName())
                    .grossAmount(payment.getGrossAmount())
                    .feeAmount(payment.getFeeAmount())
                    .unappliedAmount(payment.getUnappliedAmount())
                    .currency(payment.getCurrency())
                    .paymentMethod(
                            payment.getPaymentMethod() != null
                                    ? payment.getPaymentMethod().name()
                                    : PaymentMethod.OTHER.name())
                    .gatewayTransactionId(payment.getGatewayTransactionId())
                    .gatewayTimestamp(payment.getGatewayTimestamp())
                    .memo(payment.getMemo())
                    .allocations(savedAllocations.stream()
                            .map(a -> APPaymentGLPostingEvent.AllocationLine.builder()
                                    .allocationId(a.getAllocationId())
                                    .vendorBillId(a.getVendorBillId())
                                    .appliedAmount(a.getAppliedAmount())
                                    .allocationSequence(
                                            a.getAllocationSequence() != null ? a.getAllocationSequence() : 0)
                                    .build())
                            .toList())
                    .build();

            // Save to outbox for reliable delivery (atomic with payment transaction)
            outboxService.saveToOutbox(
                    glPostingEvent.getEventId(),
                    "APPayment",
                    payment.getPaymentId(),
                    glPostingEvent.getClass().getName(),
                    glPostingEvent);

            log.info(
                    "GL posting event persisted to outbox | paymentId={} | eventId={} | grossAmount={}",
                    payment.getPaymentId(),
                    glPostingEvent.getEventId(),
                    payment.getGrossAmount());

            log.info(
                    "Payment {} executed successfully for vendor {}, amount {}",
                    payment.getPaymentRef(),
                    payment.getVendorId(),
                    payment.getGrossAmount());

            return toResponse(payment);

        } catch (InvalidBillAllocationException e) {
            // The plan is validated before the gateway call (S13); an allocation error raised here is still never
            // a gateway failure: the payment rolls back and is not marked GATEWAY_FAILED.
            throw e;
        } catch (Exception e) {
            // Gateway-level failures: persist failure state in separate transaction for
            // audit/idempotency
            // Best effort: keep failure state even when gateway call fails.
            paymentFailurePersistenceService.persistGatewayFailure(payment.getPaymentId(), e.getMessage());
            // Exception carries context and will be logged in exception handler
            throw new PaymentGatewayException("Payment gateway communication failure", request.getPaymentRef(), e);
        }
    }

    /**
     * Validates that a duplicate payment request is truly idempotent by comparing all key financial fields: vendorId,
     * grossAmount, currency, paymentMethod, feeAmount and the bank account (CAP:550 S42, #2603). The bank account is
     * compared as resolved: an omitted {@code bankAccountId} matches when the one eligible account on the payment's
     * execution date is the stored one. If any field differs, throws IdempotencyConflictException.
     *
     * <p>Allocations are not compared as they may vary during automatic allocation; the critical financial amounts above
     * ensure the effective payment is the same.
     *
     * @param existing the existing payment record
     * @param request  the incoming payment request
     * @throws IdempotencyConflictException if key fields do not match
     */
    private void validateIdempotency(@NonNull APPayment existing, @NonNull ExecuteAPPaymentRequest request) {
        // Validate all key fields match to ensure true idempotency
        boolean vendorMatch = existing.getVendorId().equals(request.getVendorId());
        boolean grossAmountMatch = existing.getGrossAmount().compareTo(request.getGrossAmount()) == 0;
        boolean currencyMatch =
                existing.getCurrency().equalsIgnoreCase(request.getCurrency().trim());
        boolean paymentMethodMatch = existing.getPaymentMethod() == request.getPaymentMethod();

        // Compare fees (null-safe)
        boolean feeMatch = (existing.getFeeAmount() == null && request.getFeeAmount() == null)
                || (existing.getFeeAmount() != null
                        && request.getFeeAmount() != null
                        && existing.getFeeAmount().compareTo(request.getFeeAmount()) == 0);
        boolean bankAccountMatch = Objects.equals(existing.getBankAccountId(), resolvedBankAccount(existing, request));

        if (!vendorMatch
                || !grossAmountMatch
                || !currencyMatch
                || !paymentMethodMatch
                || !feeMatch
                || !bankAccountMatch) {
            throw new IdempotencyConflictException(
                    "Conflicting payload for existing paymentRef: " + request.getPaymentRef()
                            + ". Idempotent replay must match vendorId, grossAmount, currency, paymentMethod, "
                            + "feeAmount, and bankAccountId.");
        }
    }

    /** The bank account a replay names: the one given, else the one eligible account on the stored execution date. */
    private @Nullable UUID resolvedBankAccount(@NonNull APPayment existing, @NonNull ExecuteAPPaymentRequest request) {
        if (request.getBankAccountId() != null) {
            return request.getBankAccountId();
        }
        if (existing.getPaymentDate() == null) {
            return null;
        }
        return preGatewayChecks.defaultBankAccount(existing.getPaymentDate()).orElse(null);
    }

    /**
     * The allocation plan, built and checked before the payment row is saved and before the gateway call (CAP:550
     * S13, #2510): explicit allocations when the request gives them, otherwise the vendor's approved bills oldest due
     * first. Its bills are locked in id order (#2509 review, A3), every bill is validated and the total checked here,
     * so a refusal moves no money.
     */
    private @NonNull List<PlannedAllocation> plan(@NonNull ExecuteAPPaymentRequest request) {
        List<PlannedAllocation> plan = hasExplicitAllocations(request) ? explicitPlan(request) : automaticPlan(request);
        BigDecimal totalAllocated =
                plan.stream().map(PlannedAllocation::appliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        validateTotalAllocations(totalAllocated, request.getGrossAmount());
        return plan;
    }

    /** Allocates the payment as {@code plan} decided, in its order. */
    private void applyAllocations(@NonNull APPayment payment, @NonNull List<PlannedAllocation> plan) {
        List<APPaymentAllocation> allocations = new ArrayList<>();
        int sequence = 1;
        BigDecimal totalAllocated = BigDecimal.ZERO;
        for (PlannedAllocation planned : plan) {
            APPaymentAllocation allocation = new APPaymentAllocation();
            allocation.setPayment(payment);
            allocation.setVendorBill(planned.bill());
            allocation.setAppliedAmount(planned.appliedAmount());
            allocation.setAllocationSequence(sequence++);
            allocations.add(allocation);
            totalAllocated = totalAllocated.add(planned.appliedAmount());
        }
        payment.setUnappliedAmount(payment.getGrossAmount().subtract(totalAllocated));
        allocationRepository.saveAll(allocations);
        paymentRepository.save(payment);
    }

    private boolean hasExplicitAllocations(@NonNull ExecuteAPPaymentRequest request) {
        List<ExecuteAPPaymentRequest.AllocationLineRequest> allocations = request.getAllocations();
        return allocations != null && !allocations.isEmpty();
    }

    private @NonNull List<PlannedAllocation> explicitPlan(@NonNull ExecuteAPPaymentRequest request) {
        List<PlannedAllocation> allocations = new ArrayList<>();
        // Every bill locked in id order before any status is read (#2509 review, A3): a void or another payment of
        // the same bills waits, and the status read here is the one the allocation commits against.
        Map<UUID, VendorBill> locked = billRepository
                .lockByVendorBillIdIn(request.getAllocations().stream()
                        .map(ExecuteAPPaymentRequest.AllocationLineRequest::getVendorBillId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(VendorBill::getVendorBillId, Function.identity()));

        for (ExecuteAPPaymentRequest.AllocationLineRequest allocationLine : request.getAllocations()) {
            VendorBill bill = validateBillForAllocation(
                    allocationLine.getVendorBillId(),
                    locked.get(allocationLine.getVendorBillId()),
                    request.getVendorId());
            if (allocationLine.getAppliedAmount().signum() != 0) {
                // A line of 0.00 pays nothing: it is no allocation and blocks nothing (#2622 review LOW-3).
                allocations.add(new PlannedAllocation(bill, allocationLine.getAppliedAmount()));
            }
        }

        return allocations;
    }

    private @NonNull List<PlannedAllocation> automaticPlan(@NonNull ExecuteAPPaymentRequest request) {
        List<VendorBill> eligibleBills = getEligibleBillsSortedByDueDate(request.getVendorId());
        List<PlannedAllocation> allocations = new ArrayList<>();
        BigDecimal remaining = request.getGrossAmount();

        for (VendorBill bill : eligibleBills) {
            // Stop if no remaining amount to allocate
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal billOpen = calculateOpenAmount(bill.getVendorBillId());
            // Process only bills with positive open amount
            if (billOpen.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal toApply = remaining.min(billOpen);
                allocations.add(new PlannedAllocation(bill, toApply));
                remaining = remaining.subtract(toApply);
            }
        }

        return allocations;
    }

    private @NonNull VendorBill validateBillForAllocation(
            @NonNull UUID vendorBillId, @Nullable VendorBill bill, @NonNull UUID expectedVendorId) {
        if (bill == null) {
            throw new InvalidBillAllocationException("Bill not found: " + vendorBillId);
        }

        if (bill.getStatus() != VendorBillStatus.APPROVED) {
            throw new InvalidBillAllocationException("Bill " + vendorBillId + " is not approved for payment");
        }

        if (!bill.getVendorId().equals(expectedVendorId)) {
            throw new InvalidBillAllocationException(
                    "Bill " + vendorBillId + " does not belong to vendor " + expectedVendorId);
        }

        return bill;
    }

    private @NonNull List<VendorBill> getEligibleBillsSortedByDueDate(@NonNull UUID vendorId) {
        // Locked in id order, the status evaluated on the locked rows (#2509 review, A3), then sorted for allocation.
        List<VendorBill> bills =
                new ArrayList<>(billRepository.lockByVendorIdAndStatus(vendorId, VendorBillStatus.APPROVED));
        bills.sort(Comparator.comparing(VendorBill::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VendorBill::getBillDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VendorBill::getVendorBillId));
        return bills;
    }

    private void validateTotalAllocations(@NonNull BigDecimal totalAllocated, @NonNull BigDecimal grossAmount) {
        if (totalAllocated.compareTo(grossAmount) > 0) {
            throw new InvalidBillAllocationException("Total allocations exceed gross payment amount");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<APPaymentResponse> getPaymentById(@NonNull UUID paymentId) {
        return paymentRepository.findById(paymentId).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<APPaymentResponse> getPaymentByRef(@NonNull String paymentRef) {
        return paymentRepository.findByPaymentRef(paymentRef).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<VendorBillSummaryResponse> listEligibleBills(
            @Nullable UUID vendorId, @NonNull Pageable pageable) {
        Page<VendorBill> billsPage = vendorId == null
                ? billRepository.findByStatusAndOpenAmountGreaterThan(
                        VendorBillStatus.APPROVED, BigDecimal.ZERO, pageable)
                : billRepository.findByVendorIdAndStatusAndOpenAmountGreaterThan(
                        vendorId, VendorBillStatus.APPROVED, BigDecimal.ZERO, pageable);

        if (billsPage == null) {
            billsPage = Page.empty(pageable);
        }

        return billsPage.map(this::toBillSummary);
    }

    @Override
    @Transactional
    public void acknowledgeGLPosted(@NonNull UUID paymentId, @NonNull UUID journalEntryId) {
        // (d) Defensive/internal invariant: this service method is not currently called from
        // any controller (no HTTP path invokes it today — it exists for a future GL-posting
        // acknowledgement callback). Left as IllegalArgumentException; falls through to the
        // pos-web-common catch-all (correlated 500) if that ever changes without this being
        // revisited.
        APPayment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        JournalEntry journalEntry = journalEntryRepository
                .findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + journalEntryId));

        payment.setGlJournalEntry(journalEntry);
        payment.setGlPostedAt(Instant.now(clock));
        payment.setStatus(APPaymentStatus.GL_POSTED);
        paymentRepository.save(payment);

        log.info("GL posting acknowledged for payment {}, journal entry {}", paymentId, journalEntryId);
    }

    @Override
    @Transactional
    public void recordGLPostFailure(@NonNull UUID paymentId, @NonNull String errorMessage) {
        // (d) Defensive/internal invariant: not currently called from any controller (same
        // reasoning as acknowledgeGLPosted above).
        APPayment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setGlPostError(errorMessage);
        paymentRepository.save(payment);

        log.error("GL posting failed for payment {}: {}", paymentId, errorMessage);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs no transaction of its own: the posting's ({@link APPaymentPostingService#retry}) rolls back on a refusal,
     * and the refusal's code is then recorded in a transaction of its own, so the payment stays {@code GL_POST_FAILED}
     * with the new reason.
     */
    @Override
    public @NonNull APPaymentResponse retryGLPosting(@NonNull UUID paymentId, @Nullable String overrideJustification) {
        try {
            postingService.retry(paymentId, overrideJustification);
        } catch (RuntimeException e) {
            APPaymentPostingService.refusalCode(e)
                    .ifPresent(code -> paymentFailurePersistenceService.persistGLPostRefusal(paymentId, code));
            throw e;
        }
        return paymentRepository
                .findById(paymentId)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("AP payment " + paymentId + " vanished after posting"));
    }

    private @NonNull APPaymentResponse toResponse(@NonNull APPayment payment) {
        List<APPaymentAllocation> allocations =
                allocationRepository.findByPayment_PaymentIdOrderByAllocationSequenceAsc(payment.getPaymentId());

        return APPaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .paymentRef(payment.getPaymentRef())
                .vendorId(payment.getVendorId())
                .vendorName(payment.getVendorName())
                .grossAmount(payment.getGrossAmount())
                .feeAmount(payment.getFeeAmount())
                .unappliedAmount(payment.getUnappliedAmount())
                .currency(payment.getCurrency())
                .bankAccountId(payment.getBankAccountId())
                .paymentDate(payment.getPaymentDate())
                .status(payment.getStatus())
                .gatewayTransactionId(payment.getGatewayTransactionId())
                .gatewayTimestamp(payment.getGatewayTimestamp())
                .glJournalEntryId(payment.getGlJournalEntryId())
                .glPostedAt(payment.getGlPostedAt())
                .glPostError(payment.getGlPostError())
                .memo(payment.getMemo())
                .allocations(allocations.stream()
                        .map(a -> APPaymentResponse.AllocationLineResponse.builder()
                                .allocationId(a.getAllocationId())
                                .vendorBillId(a.getVendorBillId())
                                .appliedAmount(a.getAppliedAmount())
                                .allocationSequence(a.getAllocationSequence())
                                .build())
                        .toList())
                .createdAt(payment.getCreatedAt())
                .createdBy(payment.getCreatedBy())
                .build();
    }

    private @NonNull VendorBillSummaryResponse toBillSummary(@NonNull VendorBill bill) {
        // Calculate actual openAmount (totalAmount - sum of allocations)
        BigDecimal openAmount = calculateOpenAmount(bill.getVendorBillId());

        return VendorBillSummaryResponse.builder()
                .vendorBillId(bill.getVendorBillId())
                .vendorId(bill.getVendorId())
                .vendorName(bill.getVendorName())
                .billNumber(bill.getBillNumber())
                .billDate(bill.getBillDate())
                .dueDate(bill.getDueDate())
                .totalAmount(bill.getTotalAmount())
                .openAmount(openAmount)
                .status(bill.getStatus())
                .build();
    }

    /**
     * Calculates the open (unpaid) amount for a vendor bill.
     *
     * Uses an aggregate database query to efficiently compute the sum of
     * allocations
     * without loading all allocation records into memory.
     *
     * @param vendorBillId the bill ID
     * @return open amount = totalAmount - sum of all allocations
     */
    private @NonNull BigDecimal calculateOpenAmount(@NonNull UUID vendorBillId) {
        // (d) Defensive/internal invariant: both call sites pass a vendorBillId taken from a
        // VendorBill entity already loaded in the same transaction, so this re-fetch cannot
        // genuinely miss under normal operation.
        VendorBill bill = billRepository
                .findById(vendorBillId)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + vendorBillId));

        // Use aggregate query to sum allocations in database (avoids N+1 and high
        // memory)
        BigDecimal totalAllocated = allocationRepository.sumAllocatedAmountByVendorBillId(vendorBillId);

        return bill.getTotalAmount().subtract(totalAllocated);
    }
}
