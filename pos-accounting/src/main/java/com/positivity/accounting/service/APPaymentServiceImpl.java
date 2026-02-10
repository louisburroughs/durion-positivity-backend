package com.positivity.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;

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

    private final APPaymentRepository paymentRepository;
    private final APPaymentAllocationRepository allocationRepository;
    private final VendorBillRepository billRepository;

    @Override
    @Transactional
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

        // Simulate gateway call (TODO: integrate actual payment gateway)
        payment.setStatus(APPaymentStatus.GATEWAY_PENDING);
        payment = paymentRepository.save(payment);

        try {
            // Simulate successful gateway response
            payment.setGatewayTransactionId("sim_" + UUID.randomUUID().toString().substring(0, 8));
            payment.setGatewayTimestamp(Instant.now());
            payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
            payment = paymentRepository.save(payment);

            // Apply allocations (validation errors bubble up as IllegalArgumentException)
            applyAllocations(payment, request);

            // Emit event for GL posting (TODO: integrate with outbox pattern)
            payment.setStatus(APPaymentStatus.GL_POST_PENDING);
            payment = paymentRepository.save(payment);

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
            persistGatewayFailure(payment.getPaymentId(), e.getMessage());
            log.error("Payment {} gateway failed: {}", request.getPaymentRef(), e.getMessage(), e);
            throw new PaymentGatewayException("Gateway failure: " + e.getMessage(), e);
        }
    }

    /**
     * Persists gateway failure state in a separate transaction.
     * 
     * This method uses REQUIRES_NEW propagation to ensure the failure state is
     * persisted
     * even when the parent transaction rolls back. This is critical for audit
     * trails and
     * idempotency - we need to record that a payment attempt was made and failed.
     * 
     * @param paymentId    the payment ID
     * @param errorMessage the error message from the gateway
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persistGatewayFailure(@NonNull UUID paymentId, String errorMessage) {
        try {
            Optional<APPayment> paymentOpt = paymentRepository.findById(paymentId);
            if (paymentOpt.isPresent()) {
                APPayment payment = paymentOpt.get();
                payment.setStatus(APPaymentStatus.GATEWAY_FAILED);
                payment.setGatewayResponse(errorMessage);
                paymentRepository.save(payment);
                log.info("Persisted gateway failure for payment {}", paymentId);
            } else {
                log.warn("Could not find payment {} to persist gateway failure", paymentId);
            }
        } catch (Exception ex) {
            log.error("Failed to persist gateway failure for payment {}: {}", paymentId, ex.getMessage(), ex);
            // Don't throw - this is best-effort persistence
        }
    }

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

        // Note: Allocations are not compared as they may vary during automatic
        // allocation;
        // the critical financial amounts above ensure the effective payment is the
        // same.
    }

    private void applyAllocations(@NonNull APPayment payment, @NonNull ExecuteAPPaymentRequest request) {
        List<APPaymentAllocation> allocations = new ArrayList<>();
        BigDecimal totalAllocated = BigDecimal.ZERO;

        if (request.getAllocations() != null && !request.getAllocations().isEmpty()) {
            // Explicit allocations provided
            int sequence = 1;
            for (ExecuteAPPaymentRequest.AllocationLineRequest allocationLine : request.getAllocations()) {
                // Validate bill exists and is payable
                VendorBill bill = billRepository.findById(allocationLine.getVendorBillId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Bill not found: " + allocationLine.getVendorBillId()));

                if (bill.getStatus() != VendorBillStatus.APPROVED) {
                    throw new IllegalArgumentException(
                            "Bill " + allocationLine.getVendorBillId() + " is not approved for payment");
                }

                if (!bill.getVendorId().equals(payment.getVendorId())) {
                    throw new IllegalArgumentException(
                            "Bill " + allocationLine.getVendorBillId() + " does not belong to vendor "
                                    + payment.getVendorId());
                }
                APPaymentAllocation allocation = new APPaymentAllocation(
                        payment.getPaymentId(),
                        allocationLine.getVendorBillId(),
                        allocationLine.getAppliedAmount());
                allocation.setAllocationSequence(sequence++);
                allocations.add(allocation);
                totalAllocated = totalAllocated.add(allocationLine.getAppliedAmount());
            }
        } else {
            // Automatic allocation: oldest due first
            List<VendorBill> eligibleBills = billRepository.findByVendorIdAndStatus(
                    payment.getVendorId(), VendorBillStatus.APPROVED);

            // Sort by due date (nulls last), then bill date, then bill ID
            eligibleBills.sort(Comparator
                    .comparing(VendorBill::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(VendorBill::getBillDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(VendorBill::getVendorBillId));

            BigDecimal remaining = payment.getGrossAmount();
            int sequence = 1;

            for (VendorBill bill : eligibleBills) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                // Calculate actual open amount (totalAmount - sum of prior allocations)
                BigDecimal billOpen = calculateOpenAmount(bill.getVendorBillId());
                // Skip bills that are fully paid or over-allocated (no positive open amount)
                if (billOpen == null || billOpen.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                BigDecimal toApply = remaining.min(billOpen);
                // Only create allocations with a strictly positive applied amount
                if (toApply.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                APPaymentAllocation allocation = new APPaymentAllocation(
                        payment.getPaymentId(),
                        bill.getVendorBillId(),
                        toApply);
                allocation.setAllocationSequence(sequence++);
                allocations.add(allocation);

                totalAllocated = totalAllocated.add(toApply);
                remaining = remaining.subtract(toApply);
            }
        }

        // Validate sum of allocations <= gross amount
        if (totalAllocated.compareTo(payment.getGrossAmount()) > 0) {
            throw new IllegalArgumentException("Total allocations exceed gross payment amount");
        }

        // Calculate unapplied remainder
        BigDecimal unapplied = payment.getGrossAmount().subtract(totalAllocated);
        payment.setUnappliedAmount(unapplied);

        // Save allocations
        allocationRepository.saveAll(allocations);
        paymentRepository.save(payment);
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
                .collect(Collectors.toList());
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
                        .collect(Collectors.toList()))
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
