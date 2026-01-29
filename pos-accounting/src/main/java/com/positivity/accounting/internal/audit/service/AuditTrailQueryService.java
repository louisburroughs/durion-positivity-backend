package com.positivity.accounting.internal.audit.service;

import com.positivity.accounting.internal.audit.dto.AuditTrailResponse;
import com.positivity.accounting.internal.audit.entity.AuditTrailEntry;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for querying audit trail entries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AuditTrailQueryService {
    
    private final AuditTrailEntryRepository auditRepository;
    
    /**
     * Get all audit entries for an order.
     */
    public List<AuditTrailResponse> getByOrderId(UUID orderId) {
        log.info("Querying audit entries for order {}", orderId);
        return auditRepository.findByOrderIdOrderByTimestampAsc(orderId)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all audit entries for an invoice.
     */
    public List<AuditTrailResponse> getByInvoiceId(UUID invoiceId) {
        log.info("Querying audit entries for invoice {}", invoiceId);
        return auditRepository.findByInvoiceIdOrderByTimestampAsc(invoiceId)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get audit entries by exception type within a date range.
     */
    public List<AuditTrailResponse> getByTypeAndDateRange(ExceptionType type, 
                                                          Instant startDate, 
                                                          Instant endDate) {
        log.info("Querying audit entries for type {} between {} and {}", 
                type, startDate, endDate);
        return auditRepository.findByExceptionTypeAndDateRange(type, startDate, endDate)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get audit entries by actor within a date range.
     */
    public List<AuditTrailResponse> getByActorAndDateRange(UUID actorId, 
                                                           Instant startDate, 
                                                           Instant endDate) {
        log.info("Querying audit entries for actor {} between {} and {}", 
                actorId, startDate, endDate);
        return auditRepository.findByActorAndDateRange(actorId, startDate, endDate)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all audit entries within a date range.
     */
    public List<AuditTrailResponse> getByDateRange(Instant startDate, Instant endDate) {
        log.info("Querying audit entries between {} and {}", startDate, endDate);
        return auditRepository.findByDateRange(startDate, endDate)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    private AuditTrailResponse mapToResponse(AuditTrailEntry entry) {
        return AuditTrailResponse.builder()
            .auditId(entry.getAuditId())
            .exceptionType(entry.getExceptionType())
            .actorId(entry.getActorId())
            .actorRole(entry.getActorRole())
            .timestamp(entry.getTimestamp())
            .reason(entry.getReason())
            .authorizationLevel(entry.getAuthorizationLevel())
            .policyVersion(entry.getPolicyVersion())
            .orderId(entry.getOrderId())
            .lineItemId(entry.getLineItemId())
            .originalPrice(entry.getOriginalPrice())
            .adjustedPrice(entry.getAdjustedPrice())
            .overrideAmountOrPercent(entry.getOverrideAmountOrPercent())
            .forbiddenCategoryCode(entry.getForbiddenCategoryCode())
            .policyValidationResult(entry.getPolicyValidationResult())
            .invoiceId(entry.getInvoiceId())
            .paymentId(entry.getPaymentId())
            .refundType(entry.getRefundType())
            .refundAmount(entry.getRefundAmount())
            .originalPaymentStatus(entry.getOriginalPaymentStatus())
            .refundMethod(entry.getRefundMethod())
            .linkedSourceIds(entry.getLinkedSourceIds())
            .cancellationType(entry.getCancellationType())
            .beforeSnapshot(entry.getBeforeSnapshot())
            .afterSnapshot(entry.getAfterSnapshot())
            .partialPaymentInfo(entry.getPartialPaymentInfo())
            .glReversalStatus(entry.getGlReversalStatus())
            .accountingIntent(entry.getAccountingIntent())
            .accountingStatus(entry.getAccountingStatus())
            .expectedAccountingOutcome(entry.getExpectedAccountingOutcome())
            .sourceEventId(entry.getSourceEventId())
            .sourceDocumentId(entry.getSourceDocumentId())
            .build();
    }
}
