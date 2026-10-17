package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.dto.APPaymentGLPostingRetryRequest;
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.AllocateAPPaymentRequest;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.ReconcileAPPaymentGatewayRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.APPaymentAllocationRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidBillAllocationException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.payment.GatewayPaymentRequest;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentAllocationRequestRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityNotFoundException;
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
    private final APPaymentAllocationRequestRepository allocationRequests;
    private final AccountingAuditLogRepository auditLogs;

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
        //        1d. the vendor: not in the copy -> 503 VENDOR_REPLICATION_PENDING, INACTIVE -> 422 VENDOR_INACTIVE
        // (S24, AW23;
        //            an inactive vendor's existing bills are not paid either, ruling 3 of #2517);
        //        1e. the AP hold: the vendor on hold -> 422 VENDOR_ON_AP_HOLD (#2615, ruling 6072661753 item 4); after
        //            1d, so an inactive vendor that is also held answers VENDOR_INACTIVE; no plan is needed, since one
        //            payment pays one vendor; the settings row is read unlocked (the hold is forward-looking);
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
        vendorCopies.requireNotOnHold(vendor, request.getPaymentRef()); // 1e
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
            enqueueGlPosting(payment);

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

    @Override
    @Transactional
    public APPaymentResponse allocateCashPayment(
            @NonNull UUID paymentId, @NonNull AllocateAPPaymentRequest request, @NonNull String currentUser) {
        lockTimeout.apply();
        String hash = allocationHash(paymentId, request);
        // The payment row lock first, the replay check behind it: two calls with one requestId serialise here, and
        // the second sees the first's request row instead of allocating again and colliding on its insert.
        APPayment payment = paymentRepository.lockById(paymentId).orElseThrow(() -> notFound(paymentId));
        APPaymentAllocationRequest earlier =
                allocationRequests.findByRequestId(request.getRequestId()).orElse(null);
        if (earlier != null) {
            if (!earlier.getPaymentId().equals(paymentId)
                    || !earlier.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(
                        "requestId " + request.getRequestId() + " was already used with a different payload");
            }
            return toResponse(payment);
        }
        requireDistinctBills(request.getAllocations());
        if (payment.getPaymentMethod() != PaymentMethod.CASH) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_NOT_ALLOCATABLE,
                    "AP payment " + payment.getPaymentRef() + " is a " + payment.getPaymentMethod()
                            + " payment; only a drawer cash-on-delivery (CASH) payment is allocated later");
        }
        BigDecimal unapplied = payment.getUnappliedAmount() == null ? BigDecimal.ZERO : payment.getUnappliedAmount();
        if (unapplied.signum() <= 0) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_NOT_ALLOCATABLE,
                    "AP payment " + payment.getPaymentRef() + " has nothing unapplied");
        }
        // The same plan as the pay command's explicit allocations: bills locked in id order, each APPROVED and the
        // vendor's, zero lines ignored; the total bounded by what is still unapplied (not the gross).
        List<PlannedAllocation> plan = explicitPlan(payment.getVendorId(), request.getAllocations());
        BigDecimal total = plan.stream().map(PlannedAllocation::appliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            throw new InvalidBillAllocationException("The allocations apply nothing");
        }
        if (total.compareTo(unapplied) > 0) {
            throw new InvalidBillAllocationException("Allocations total " + total.toPlainString()
                    + " exceeds the payment's unapplied amount " + unapplied.toPlainString());
        }
        for (PlannedAllocation planned : plan) {
            BigDecimal open = calculateOpenAmount(planned.bill());
            if (planned.appliedAmount().compareTo(open) > 0) {
                throw new InvalidBillAllocationException(
                        "Allocation " + planned.appliedAmount().toPlainString()
                                + " to bill " + planned.bill().getVendorBillId() + " exceeds its open amount "
                                + open.toPlainString());
            }
        }
        // Approver ≠ payer on every allocation, cash on delivery included (AW6.2, §4.9).
        payGuard.check(plan.stream().map(PlannedAllocation::bill).toList(), currentUser, payment.getPaymentRef());
        appendAllocations(payment, plan);
        allocationRequests.save(new APPaymentAllocationRequest(request.getRequestId(), paymentId, hash));
        log.info(
                "CASH AP payment allocated | paymentId={} | bills={} | total={} | unappliedAfter={}",
                paymentId,
                plan.size(),
                total,
                payment.getUnappliedAmount());
        return toResponse(payment);
    }

    /**
     * One line per bill: a bill named twice would pass the per-line open-amount check with each line alone and end
     * over-allocated once both are written.
     */
    private static void requireDistinctBills(List<ExecuteAPPaymentRequest.AllocationLineRequest> lines) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (ExecuteAPPaymentRequest.AllocationLineRequest line : lines) {
            if (line.getVendorBillId() != null && !seen.add(line.getVendorBillId())) {
                throw new InvalidBillAllocationException(
                        "Bill " + line.getVendorBillId() + " is named more than once; send one line per bill");
            }
        }
    }

    private static String allocationHash(UUID paymentId, AllocateAPPaymentRequest request) {
        RegisterFloatServiceImpl.Hash hash =
                new RegisterFloatServiceImpl.Hash().field("ALLOCATE").field(paymentId);
        for (ExecuteAPPaymentRequest.AllocationLineRequest line : request.getAllocations()) {
            hash.field(line.getVendorBillId()).field(line.getAppliedAmount());
        }
        return hash.digest();
    }

    private static EntityNotFoundException notFound(UUID paymentId) {
        return new EntityNotFoundException("AP payment not found: " + paymentId);
    }

    /** Adds {@code plan} after the payment's existing allocations and takes the total off what is unapplied. */
    private void appendAllocations(@NonNull APPayment payment, @NonNull List<PlannedAllocation> plan) {
        List<APPaymentAllocation> existing =
                allocationRepository.findByPayment_PaymentIdOrderByAllocationSequenceAsc(payment.getPaymentId());
        int sequence = existing.size() + 1;
        List<APPaymentAllocation> allocations = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PlannedAllocation planned : plan) {
            APPaymentAllocation allocation = new APPaymentAllocation();
            allocation.setPayment(payment);
            allocation.setVendorBill(planned.bill());
            allocation.setAppliedAmount(planned.appliedAmount());
            allocation.setAllocationSequence(sequence++);
            allocations.add(allocation);
            total = total.add(planned.appliedAmount());
        }
        payment.setUnappliedAmount(payment.getUnappliedAmount().subtract(total));
        allocationRepository.saveAll(allocations);
        paymentRepository.save(payment);
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
        return explicitPlan(request.getVendorId(), request.getAllocations());
    }

    private @NonNull List<PlannedAllocation> explicitPlan(
            @NonNull UUID vendorId, @NonNull List<ExecuteAPPaymentRequest.AllocationLineRequest> lines) {
        List<PlannedAllocation> allocations = new ArrayList<>();
        // Every bill locked in id order before any status is read (#2509 review, A3): a void or another payment of
        // the same bills waits, and the status read here is the one the allocation commits against.
        Map<UUID, VendorBill> locked = billRepository
                .lockByVendorBillIdIn(lines.stream()
                        .map(ExecuteAPPaymentRequest.AllocationLineRequest::getVendorBillId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(VendorBill::getVendorBillId, Function.identity()));

        for (ExecuteAPPaymentRequest.AllocationLineRequest allocationLine : lines) {
            VendorBill bill = validateBillForAllocation(
                    allocationLine.getVendorBillId(), locked.get(allocationLine.getVendorBillId()), vendorId);
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
        // Held vendors' bills stay listed, flagged (#2615); one settings query per page.
        Map<UUID, String> heldVendors = vendorCopies.heldVendors(
                billsPage.getContent().stream().map(VendorBill::getVendorId).collect(Collectors.toSet()));

        return billsPage.map(bill -> toBillSummary(bill, heldVendors));
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

    /** Queues the payment's GL posting event (atomic with the caller's transaction). */
    private void enqueueGlPosting(@NonNull APPayment payment) {
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
                                .allocationSequence(a.getAllocationSequence() != null ? a.getAllocationSequence() : 0)
                                .build())
                        .toList())
                .build();
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
    }

    @Override
    @Transactional
    public @NonNull APPaymentResponse reconcileGateway(
            @NonNull UUID paymentId, @NonNull ReconcileAPPaymentGatewayRequest request, @NonNull String currentUser) {
        lockTimeout.apply();
        APPayment payment = paymentRepository.lockById(paymentId).orElseThrow(() -> notFound(paymentId));
        if (payment.getStatus() != APPaymentStatus.GATEWAY_PENDING) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_NOT_VOIDABLE,
                    "AP payment " + payment.getPaymentRef() + " is " + payment.getStatus()
                            + "; only a GATEWAY_PENDING orphan (no gateway answer recorded) is reconciled here",
                    List.of(),
                    notVoidableRemedy(payment));
        }
        String before = payment.getStatus().name();
        if (request.getGatewayOutcome() == ReconcileAPPaymentGatewayRequest.GatewayOutcome.NOT_CHARGED) {
            // No money moved: void. Nothing was posted (the posting follows the gateway answer) and no allocation
            // exists yet (they are applied after a gateway success), so there is nothing to reverse or reopen.
            payment.setStatus(APPaymentStatus.VOIDED);
            payment.setVoidReason(request.getJustification());
            payment.setVoidedAt(Instant.now(clock));
            payment.setVoidedBy(currentUser);
            payment.setGatewayResponse("Reconciled by operator: NOT_CHARGED");
        } else {
            if (request.getGatewayTransactionId() == null
                    || request.getGatewayTransactionId().isBlank()) {
                throw new VendorBillException(
                        VendorBillException.Code.AP_PAYMENT_GATEWAY_OUTCOME_REQUIRED,
                        "CHARGED needs the gateway's transaction id for AP payment " + payment.getPaymentRef());
            }
            // Money moved: the payment posts as an unapplied payment (the plan the pay command built in memory is
            // lost with the crash); the vendor's bills are allocated to it later, as cash-on-delivery payments are.
            payment.setGatewayTransactionId(request.getGatewayTransactionId().trim());
            payment.setGatewayTimestamp(Instant.now(clock));
            payment.setGatewayResponse("Reconciled by operator: CHARGED");
            payment.setUnappliedAmount(payment.getGrossAmount());
            payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        }
        APPayment saved = paymentRepository.save(payment);
        if (saved.getStatus() == APPaymentStatus.GL_POST_PENDING) {
            enqueueGlPosting(saved);
        }
        audit(
                saved,
                "AP_PAYMENT_RECONCILE_GATEWAY",
                currentUser,
                before,
                saved.getStatus().name() + ";outcome=" + request.getGatewayOutcome()
                        + (saved.getGatewayTransactionId() == null
                                ? ""
                                : ";gatewayTransactionId=" + saved.getGatewayTransactionId())
                        + ";justification=" + request.getJustification());
        log.info(
                "AP payment reconciled with the gateway | paymentId={} | outcome={} | status={} | by={}",
                paymentId,
                request.getGatewayOutcome(),
                saved.getStatus(),
                currentUser);
        return toResponse(saved);
    }

    /** The remedy for a payment that is not a GATEWAY_PENDING orphan (#2729 ruling 2). */
    private static @NonNull String notVoidableRemedy(@NonNull APPayment payment) {
        return switch (payment.getStatus()) {
            case GL_POST_FAILED ->
                "PERIOD_HARD_LOCKED".equals(payment.getGlPostError())
                        ? "Post it again with retryApPaymentGlPosting and deferPastHardLock: true"
                        : "Post it again with retryApPaymentGlPosting once glPostError is fixed";
            case GL_POST_PENDING ->
                "Wait for the outbox delivery, or retryApPaymentGlPosting if its outbox row is FAILED";
            case GL_POSTED, GATEWAY_SUCCEEDED ->
                "The charge moved money: a return is #2642 (mirror entry, reopened allocations)";
            case VOIDED -> "Already voided; nothing to do";
            case GATEWAY_FAILED -> "The gateway declined it; nothing moved and nothing to void";
            default -> "Nothing to reconcile";
        };
    }

    private void audit(APPayment payment, String operation, String user, String oldValue, String newValue) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType("AP_PAYMENT");
        row.setEntityId(payment.getPaymentId());
        row.setOperation(operation);
        row.setUserId(user);
        row.setOldValue(oldValue);
        row.setNewValue(newValue.length() > 2000 ? newValue.substring(0, 2000) : newValue);
        auditLogs.save(row);
    }

    @Override
    public @NonNull APPaymentResponse retryGLPosting(
            @NonNull UUID paymentId, @NonNull APPaymentGLPostingRetryRequest request, @NonNull String currentUser) {
        boolean defer = Boolean.TRUE.equals(request.getDeferPastHardLock());
        if (defer
                && (request.getJustification() == null
                        || request.getJustification().isBlank())) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_DEFER_JUSTIFICATION_REQUIRED,
                    "deferPastHardLock needs a justification: the entry will be dated today, not the payment's date");
        }
        try {
            postingService.retry(paymentId, request.getOverrideJustification(), defer);
        } catch (RuntimeException e) {
            APPaymentPostingService.refusalCode(e)
                    .ifPresent(code -> paymentFailurePersistenceService.persistGLPostRefusal(paymentId, code));
            throw e;
        }
        APPayment posted = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("AP payment " + paymentId + " vanished after posting"));
        if (defer && APPaymentPostingService.DEFERRED_PAST_HARD_LOCK.equals(posted.getPostingDateRule())) {
            audit(
                    posted,
                    "AP_PAYMENT_GL_POSTING_DEFERRED",
                    currentUser,
                    "paymentDate=" + posted.getPaymentDate(),
                    "journalEntryId=" + posted.getGlJournalEntryId() + ";rule="
                            + APPaymentPostingService.DEFERRED_PAST_HARD_LOCK + ";justification="
                            + request.getJustification());
        }
        return toResponse(posted);
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
                .paymentMethod(payment.getPaymentMethod())
                .registerSessionId(payment.getRegisterSessionId())
                .cashMovementId(payment.getCashMovementId())
                .paymentDate(payment.getPaymentDate())
                .status(payment.getStatus())
                .gatewayTransactionId(payment.getGatewayTransactionId())
                .gatewayTimestamp(payment.getGatewayTimestamp())
                .glJournalEntryId(payment.getGlJournalEntryId())
                .glPostedAt(payment.getGlPostedAt())
                .glPostError(payment.getGlPostError())
                .postingDateRule(payment.getPostingDateRule())
                .voidReason(payment.getVoidReason())
                .voidedAt(payment.getVoidedAt())
                .voidedBy(payment.getVoidedBy())
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

    private @NonNull VendorBillSummaryResponse toBillSummary(
            @NonNull VendorBill bill, @NonNull Map<UUID, String> heldVendors) {
        // Calculate actual openAmount (totalAmount - sum of allocations)
        BigDecimal openAmount = calculateOpenAmount(bill.getVendorBillId());
        String holdReason = heldVendors.get(bill.getVendorId());

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
                .vendorApHold(holdReason != null)
                .vendorApHoldReason(holdReason)
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
        return calculateOpenAmount(bill);
    }

    /** The open amount of a bill already loaded (and locked) in this transaction: no re-fetch. */
    private @NonNull BigDecimal calculateOpenAmount(@NonNull VendorBill bill) {
        // Use aggregate query to sum allocations in database (avoids N+1 and high
        // memory)
        BigDecimal totalAllocated = allocationRepository.sumAllocatedAmountByVendorBillId(bill.getVendorBillId());
        return bill.getTotalAmount().subtract(totalAllocated);
    }
}
