package com.positivity.accounting.internal.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.payment.GatewayPaymentRequest;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.service.APPaymentService;
import com.positivity.accounting.service.OutboxService;

import lombok.RequiredArgsConstructor;

/**
 * Implementation of AP Payment orchestration service.
 * 
 * @see APPaymentService
 */
@Service
@RequiredArgsConstructor
public class APPaymentServiceImpl implements APPaymentService {

    private static final Logger log = LoggerFactory.getLogger(APPaymentServiceImpl.class);
    private static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("00000000-0000-4000-a000-000000000010");

    private final APPaymentRepository paymentRepository;
    private final APPaymentAllocationRepository allocationRepository;
    private final VendorBillRepository billRepository;
    private final PaymentGatewayProvider paymentGateway;
    private final OutboxService outboxService;
    private final APPaymentFailurePersistenceService paymentFailurePersistenceService;

 
    @Override
    @Transactional
    @SuppressWarnings({ "java:S1181", "java:S2221" }) // Catching Exception is intentional for gateway-level failures
    public @NonNull APPaymentResponse executePayment(@NonNull ExecuteAPPaymentRequest request,
            @NonNull String currentUser) {
        // Check idempotency: if paymentRef exists, validate payload match and return
        // existing
        Optional<APPayment> existingPayment = paymentRepository.findByPaymentRef(request.getPaymentRef());
        if (existingPayment.isPresent()) {
            APPayment existing = existingPayment.get();
            validateIdempotency(existing, request);
            return toResponse(existing);
        }

        // Create payment entity
        APPayment payment = new APPayment();
        payment.setPaymentRef(request.getPaymentRef());
        payment.setVendorId(request.getVendorId());
        payment.setGrossAmount(request.getGrossAmount());
        payment.setFeeAmount(request.getFeeAmount());
        payment.setNetAmount(request.getNetAmount());
        payment.setCurrency(request.getCurrency());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setMemo(request.getMemo());
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
            payment.setGatewayTimestamp(Instant.now());
            payment.setGatewayResponse(gatewayResponse.getRawResponse());

            // Map gateway status to payment status
            switch (gatewayResponse.getStatus()) {
                case SUCCEEDED, AUTHORIZED -> payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
                case PENDING -> payment.setStatus(APPaymentStatus.GATEWAY_PENDING);
                case DECLINED, FAILED -> {
                    payment.setStatus(APPaymentStatus.GATEWAY_FAILED);
                    payment.setGatewayResponse(
                            "Gateway declined: " + (gatewayResponse.getFailureReason() != null
                                    ? gatewayResponse.getFailureReason()
                                    : "Unknown reason"));
                    throw new PaymentGatewayException(
                            "Payment declined by gateway: " + gatewayResponse.getFailureReason());
                }
            }

            payment = paymentRepository.save(payment);

            // Apply allocations (validation errors bubble up as IllegalArgumentException)
            applyAllocations(payment, request);

            // Persist event to outbox for at-least-once delivery guarantee
            payment.setStatus(APPaymentStatus.GL_POST_PENDING);
            payment = paymentRepository.save(payment);

            List<APPaymentAllocation> savedAllocations = allocationRepository
                    .findByPaymentIdOrderByAllocationSequenceAsc(payment.getPaymentId());

            APPaymentGLPostingEvent glPostingEvent = APPaymentGLPostingEvent.builder()
                    .eventId(UUID.randomUUID())
                    .organizationId(DEFAULT_ORGANIZATION_ID)
                    .paymentId(payment.getPaymentId())
                    .paymentRef(payment.getPaymentRef())
                    .vendorId(payment.getVendorId())
                    .vendorName(payment.getVendorName())
                    .grossAmount(payment.getGrossAmount())
                    .feeAmount(payment.getFeeAmount())
                    .netAmount(payment.getNetAmount())
                    .unappliedAmount(payment.getUnappliedAmount())
                    .currency(payment.getCurrency())
                    .paymentMethod(payment.getPaymentMethod() != null
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
                                            a.getAllocationSequence() != null
                                                    ? a.getAllocationSequence()
                                                    : 0)
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

            log.info("GL posting event persisted to outbox | paymentId={} | eventId={} | grossAmount={}",
                    payment.getPaymentId(), glPostingEvent.getEventId(), payment.getGrossAmount());

            log.info("Payment {} executed successfully for vendor {}, amount {}",
                    payment.getPaymentRef(), payment.getVendorId(), payment.getGrossAmount());

            return toResponse(payment);

        } catch (IllegalArgumentException e) {
            // Validation errors should not mark payment as GATEWAY_FAILED
            // Rollback the payment (transaction will roll back automatically)
            throw e;
        } catch (Exception e) {
            // Gateway-level failures: persist failure state in separate transaction for
            // audit/idempotency
            // Best effort: keep failure state even when gateway call fails.
            paymentFailurePersistenceService.persistGatewayFailure(payment.getPaymentId(), e.getMessage());
            // Exception carries context and will be logged in exception handler
            throw new PaymentGatewayException(
                    "Payment gateway communication failure", request.getPaymentRef(), e);
        }
    }

    /**
     * Validates that a duplicate payment request is truly idempotent by comparing
     * all key
     * financial fields.
     * <p>
     * Verifies that vendorId, grossAmount, currency, paymentMethod, feeAmount, and
     * netAmount
     * match the existing payment. If any field differs, throws
     * IdempotencyConflictException.
     * <p>
     * Note: Allocations are not compared as they may vary during automatic
     * allocation;
     * the critical financial amounts above ensure the effective payment is the
     * same.
     *
     * @param existing the existing payment record
     * @param request  the incoming payment request
     * @throws IdempotencyConflictException if key fields do not match
     */
    private void validateIdempotency(@NonNull APPayment existing, @NonNull ExecuteAPPaymentRequest request) {
        // Validate all key fields match to ensure true idempotency
        boolean vendorMatch = existing.getVendorId().equals(request.getVendorId());
        boolean grossAmountMatch = existing.getGrossAmount().compareTo(request.getGrossAmount()) == 0;
        boolean currencyMatch = existing.getCurrency().equals(request.getCurrency());
        boolean paymentMethodMatch = existing.getPaymentMethod() == request.getPaymentMethod();

        // Compare fees and net amounts (null-safe)
        boolean feeMatch = (existing.getFeeAmount() == null && request.getFeeAmount() == null) ||
                (existing.getFeeAmount() != null && request.getFeeAmount() != null &&
                        existing.getFeeAmount().compareTo(request.getFeeAmount()) == 0);
        boolean netMatch = (existing.getNetAmount() == null && request.getNetAmount() == null) ||
                (existing.getNetAmount() != null && request.getNetAmount() != null &&
                        existing.getNetAmount().compareTo(request.getNetAmount()) == 0);

        if (!vendorMatch || !grossAmountMatch || !currencyMatch || !paymentMethodMatch || !feeMatch || !netMatch) {
            throw new IdempotencyConflictException(
                    "Conflicting payload for existing paymentRef: " + request.getPaymentRef()
                            + ". Idempotent replay must match vendorId, grossAmount, currency, paymentMethod, "
                            + "feeAmount, and netAmount.");
        }
    }

    /**
     * Applies payment allocations to vendor bills based on the request.
     * Uses explicit allocations if provided, otherwise performs automatic
     * allocation to oldest bills first.
     *
     * @param payment the payment to allocate
     * @param request the payment request containing optional explicit allocations
     */
    private void applyAllocations(@NonNull APPayment payment, @NonNull ExecuteAPPaymentRequest request) {
        List<APPaymentAllocation> allocations = hasExplicitAllocations(request)
                ? createExplicitAllocations(payment, request)
                : createAutomaticAllocations(payment);

        BigDecimal totalAllocated = allocations.stream()
                .map(APPaymentAllocation::getAppliedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        validateTotalAllocations(totalAllocated, payment.getGrossAmount());

        payment.setUnappliedAmount(payment.getGrossAmount().subtract(totalAllocated));
        allocationRepository.saveAll(allocations);
        paymentRepository.save(payment);
    }

    private boolean hasExplicitAllocations(@NonNull ExecuteAPPaymentRequest request) {
        List<ExecuteAPPaymentRequest.AllocationLineRequest> allocations = request.getAllocations();
        return allocations != null && !allocations.isEmpty();
    }

    private @NonNull List<APPaymentAllocation> createExplicitAllocations(@NonNull APPayment payment,
            @NonNull ExecuteAPPaymentRequest request) {
        List<APPaymentAllocation> allocations = new ArrayList<>();
        int sequence = 1;

        for (ExecuteAPPaymentRequest.AllocationLineRequest allocationLine : request.getAllocations()) {
            validateBillForAllocation(allocationLine.getVendorBillId(), payment.getVendorId());

            APPaymentAllocation allocation = new APPaymentAllocation(
                    payment.getPaymentId(),
                    allocationLine.getVendorBillId(),
                    allocationLine.getAppliedAmount());
            allocation.setAllocationSequence(sequence++);
            allocations.add(allocation);
        }

        return allocations;
    }

    private @NonNull List<APPaymentAllocation> createAutomaticAllocations(@NonNull APPayment payment) {
        List<VendorBill> eligibleBills = getEligibleBillsSortedByDueDate(payment.getVendorId());
        List<APPaymentAllocation> allocations = new ArrayList<>();
        BigDecimal remaining = payment.getGrossAmount();
        int sequence = 1;

        for (VendorBill bill : eligibleBills) {
            // Stop if no remaining amount to allocate
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal billOpen = calculateOpenAmount(bill.getVendorBillId());
            // Process only bills with positive open amount
            if (billOpen.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal toApply = remaining.min(billOpen);

                APPaymentAllocation allocation = new APPaymentAllocation(
                        payment.getPaymentId(),
                        bill.getVendorBillId(),
                        toApply);
                allocation.setAllocationSequence(sequence++);
                allocations.add(allocation);

                remaining = remaining.subtract(toApply);
            }
        }

        return allocations;
    }

    private void validateBillForAllocation(@NonNull UUID vendorBillId, @NonNull UUID expectedVendorId) {
        VendorBill bill = billRepository.findById(vendorBillId)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + vendorBillId));

        if (bill.getStatus() != VendorBillStatus.APPROVED) {
            throw new IllegalArgumentException("Bill " + vendorBillId + " is not approved for payment");
        }

        if (!bill.getVendorId().equals(expectedVendorId)) {
            throw new IllegalArgumentException(
                    "Bill " + vendorBillId + " does not belong to vendor " + expectedVendorId);
        }
    }

    private @NonNull List<VendorBill> getEligibleBillsSortedByDueDate(@NonNull UUID vendorId) {
        List<VendorBill> bills = billRepository.findByVendorIdAndStatus(vendorId, VendorBillStatus.APPROVED);
        bills.sort(Comparator
                .comparing(VendorBill::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VendorBill::getBillDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VendorBill::getVendorBillId));
        return bills;
    }

    private void validateTotalAllocations(@NonNull BigDecimal totalAllocated, @NonNull BigDecimal grossAmount) {
        if (totalAllocated.compareTo(grossAmount) > 0) {
            throw new IllegalArgumentException("Total allocations exceed gross payment amount");
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
    public @NonNull List<VendorBillSummaryResponse> listEligibleBills(@NonNull UUID vendorId) {
        List<VendorBill> bills = billRepository.findByVendorIdAndStatus(vendorId, VendorBillStatus.APPROVED);

        // Sort by due date (oldest first, nulls last), then bill date, then bill ID
        bills.sort(Comparator
                .comparing(VendorBill::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VendorBill::getBillDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VendorBill::getVendorBillId));

        return bills.stream()
                .map(this::toBillSummary)
                .toList();
    }

    @Override
    @Transactional
    public void acknowledgeGLPosted(@NonNull UUID paymentId, @NonNull UUID journalEntryId) {
        APPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        payment.setGlJournalEntryId(journalEntryId);
        payment.setGlPostedAt(Instant.now());
        payment.setStatus(APPaymentStatus.GL_POSTED);
        paymentRepository.save(payment);

        log.info("GL posting acknowledged for payment {}, journal entry {}", paymentId, journalEntryId);
    }

    @Override
    @Transactional
    public void recordGLPostFailure(@NonNull UUID paymentId, @NonNull String errorMessage) {
        APPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setGlPostError(errorMessage);
        paymentRepository.save(payment);

        log.error("GL posting failed for payment {}: {}", paymentId, errorMessage);
    }

    private @NonNull APPaymentResponse toResponse(@NonNull APPayment payment) {
        List<APPaymentAllocation> allocations = allocationRepository
                .findByPaymentIdOrderByAllocationSequenceAsc(payment.getPaymentId());

        return APPaymentResponse.builder()
                .paymentId(payment.getPaymentId())
                .paymentRef(payment.getPaymentRef())
                .vendorId(payment.getVendorId())
                .vendorName(payment.getVendorName())
                .grossAmount(payment.getGrossAmount())
                .feeAmount(payment.getFeeAmount())
                .netAmount(payment.getNetAmount())
                .unappliedAmount(payment.getUnappliedAmount())
                .currency(payment.getCurrency())
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
        VendorBill bill = billRepository.findById(vendorBillId)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found: " + vendorBillId));

        // Use aggregate query to sum allocations in database (avoids N+1 and high
        // memory)
        BigDecimal totalAllocated = allocationRepository.sumAllocatedAmountByVendorBillId(vendorBillId);

        return bill.getTotalAmount().subtract(totalAllocated);
    }
}
