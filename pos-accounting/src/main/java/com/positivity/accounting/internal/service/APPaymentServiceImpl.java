package com.positivity.accounting.internal.service;

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
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.service.APPaymentService;

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
        payment.setMemo(request.getMemo());
        payment.setStatus(APPaymentStatus.INITIATED);
        payment.setCreatedBy(currentUser);

        // Simulate gateway call (TODO: integrate actual payment gateway)
        try {
            payment.setStatus(APPaymentStatus.GATEWAY_PENDING);
            payment = paymentRepository.save(payment);

            // Simulate successful gateway response
            payment.setGatewayTransactionId("sim_" + UUID.randomUUID().toString().substring(0, 8));
            payment.setGatewayTimestamp(Instant.now());
            payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
            payment = paymentRepository.save(payment);

            // Apply allocations
            applyAllocations(payment, request);

            // Emit event for GL posting (TODO: integrate with outbox pattern)
            payment.setStatus(APPaymentStatus.GL_POST_PENDING);
            payment = paymentRepository.save(payment);

            log.info("Payment {} executed successfully for vendor {}, amount {}",
                    payment.getPaymentRef(), payment.getVendorId(), payment.getGrossAmount());

            return toResponse(payment);

        } catch (Exception e) {
            payment.setStatus(APPaymentStatus.GATEWAY_FAILED);
            payment.setGatewayResponse(e.getMessage());
            payment = paymentRepository.save(payment);
            log.error("Payment {} failed: {}", request.getPaymentRef(), e.getMessage(), e);
            throw new RuntimeException("Gateway failure: " + e.getMessage(), e);
        }
    }

    private void validateIdempotency(@NonNull APPayment existing, @NonNull ExecuteAPPaymentRequest request) {
        // Simple validation: compare key fields
        if (!existing.getVendorId().equals(request.getVendorId()) ||
                existing.getGrossAmount().compareTo(request.getGrossAmount()) != 0) {
            throw new IllegalArgumentException(
                    "Conflicting payload for existing paymentRef: " + request.getPaymentRef());
        }
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

                BigDecimal billOpen = bill.getTotalAmount(); // TODO: calculate actual open amount
                BigDecimal toApply = remaining.min(billOpen);

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
        // TODO: Calculate actual openAmount (totalAmount - sum of allocations)
        BigDecimal openAmount = bill.getTotalAmount();

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
}
