package com.positivity.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.internal.audit.dto.AuditTrailResponse;
import com.positivity.accounting.internal.audit.dto.CancellationRequest;
import com.positivity.accounting.internal.audit.dto.PriceOverrideRequest;
import com.positivity.accounting.internal.audit.dto.RefundRequest;
import com.positivity.accounting.internal.audit.entity.AuditTrailEntry;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.entity.PolicyValidationResult;
import com.positivity.accounting.internal.audit.event.AuthorizationDenied;
import com.positivity.accounting.internal.audit.event.CancellationCreated;
import com.positivity.accounting.internal.audit.event.OverridePriceCreated;
import com.positivity.accounting.internal.audit.event.RefundCreated;
import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.CancellationType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Service for recording audit trail entries for financial exceptions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditTrailService {

        private final AuditTrailEntryRepository auditRepository;
        private final PriceOverrideAuthorizationService overrideAuthService;
        private final RefundAuthorizationService refundAuthService;
        private final ApplicationEventPublisher eventPublisher;
        private final ObjectMapper objectMapper;

        /**
         * Records a price override in the audit trail.
         * 
         * @param request the price override request
         * @return audit trail response
         * @throws AuthorizationException if not authorized
         */
        @Transactional
        public AuditTrailResponse recordPriceOverride(PriceOverrideRequest request) {
                log.info("Recording price override for order {} line {}",
                                request.getOrderId(), request.getLineItemId());

                // Validate authorization
                PriceOverrideAuthorizationService.AuthorizationResult authResult = overrideAuthService.validate(
                                request.getActorRole(),
                                request.getOriginalPrice(),
                                request.getAdjustedPrice(),
                                null // categoryCode not provided in basic request
                );

                if (!authResult.isApproved()) {
                        log.warn("Price override denied: {}", authResult.getMessage());

                        // Emit denial event
                        eventPublisher.publishEvent(AuthorizationDenied.builder()
                                        .exceptionType(ExceptionType.PRICE_OVERRIDE)
                                        .actorId(request.getActorId())
                                        .actorRole(request.getActorRole())
                                        .reasonDenied(authResult.getMessage())
                                        .policyVersion(authResult.getPolicyVersion())
                                        .build());

                        throw new AuthorizationException(authResult.getMessage());
                }

                // Calculate override amount/percent
                BigDecimal discountAmount = request.getOriginalPrice().subtract(request.getAdjustedPrice());
                BigDecimal discountPercent = discountAmount
                                .divide(request.getOriginalPrice(), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
                String overrideDesc = String.format("-$%.2f (%.1f%%)",
                                discountAmount, discountPercent);

                // Create audit entry
                AuditTrailEntry entry = AuditTrailEntry.builder()
                                .exceptionType(ExceptionType.PRICE_OVERRIDE)
                                .actorId(request.getActorId())
                                .actorRole(request.getActorRole())
                                .reason(request.getReason())
                                .authorizationLevel(authResult.getAuthorizationLevel())
                                .policyVersion(authResult.getPolicyVersion())
                                .orderId(request.getOrderId())
                                .lineItemId(request.getLineItemId())
                                .originalPrice(request.getOriginalPrice())
                                .adjustedPrice(request.getAdjustedPrice())
                                .overrideAmountOrPercent(overrideDesc)
                                .policyValidationResult(PolicyValidationResult.APPROVED)
                                .accountingIntent(AccountingIntent.REVENUE_ADJUSTMENT)
                                .accountingStatus(AccountingStatus.PENDING_POSTING)
                                .expectedAccountingOutcome("Revenue adjustment of " + overrideDesc)
                                .sourceEventId(UUID.randomUUID())
                                .sourceDocumentId("ORDER-" + request.getOrderId())
                                .build();

                entry = auditRepository.save(entry);
                log.info("Price override recorded with audit ID {}", entry.getAuditId());

                // Emit event
                eventPublisher.publishEvent(OverridePriceCreated.builder()
                                .auditId(entry.getAuditId())
                                .orderId(entry.getOrderId())
                                .lineItemId(entry.getLineItemId())
                                .originalPrice(entry.getOriginalPrice())
                                .adjustedPrice(entry.getAdjustedPrice())
                                .actorId(entry.getActorId())
                                .actorRole(entry.getActorRole())
                                .authorizationLevel(entry.getAuthorizationLevel())
                                .policyVersion(entry.getPolicyVersion())
                                .reason(entry.getReason())
                                .timestamp(entry.getTimestamp())
                                .accountingIntent(entry.getAccountingIntent())
                                .accountingStatus(entry.getAccountingStatus())
                                .expectedAccountingOutcome(entry.getExpectedAccountingOutcome())
                                .sourceEventId(entry.getSourceEventId())
                                .sourceDocumentId(entry.getSourceDocumentId())
                                .build());

                return mapToResponse(entry);
        }

        /**
         * Records a refund in the audit trail.
         * 
         * @param request the refund request
         * @return audit trail response
         * @throws AuthorizationException if not authorized
         */
        @Transactional
        public AuditTrailResponse recordRefund(RefundRequest request) {
                log.info("Recording refund for invoice {} payment {}",
                                request.getInvoiceId(), request.getPaymentId());

                // Validate authorization
                RefundAuthorizationService.RefundAuthorizationResult authResult = refundAuthService.validate(
                                request.getActorRole(),
                                request.getOriginalPaymentStatus(),
                                request.getRefundType());

                if (!authResult.isAuthorized()) {
                        log.warn("Refund denied: {}", authResult.getMessage());

                        // Emit denial event
                        eventPublisher.publishEvent(AuthorizationDenied.builder()
                                        .exceptionType(ExceptionType.REFUND)
                                        .actorId(request.getActorId())
                                        .actorRole(request.getActorRole())
                                        .reasonDenied(authResult.getMessage())
                                        .policyVersion(authResult.getPolicyVersion())
                                        .build());

                        throw new AuthorizationException(authResult.getMessage());
                }

                // Determine accounting intent based on refund type
                AccountingIntent accountingIntent = switch (request.getRefundType()) {
                        case REVERSAL -> AccountingIntent.PAYMENT_REVERSAL;
                        case CREDIT_MEMO -> AccountingIntent.CUSTOMER_CREDIT;
                        case ADJUSTMENT -> AccountingIntent.WRITE_OFF;
                };

                // Build linked source IDs
                Map<String, String> linkedIds = new HashMap<>();
                linkedIds.put("invoiceId", request.getInvoiceId().toString());
                linkedIds.put("paymentId", request.getPaymentId().toString());
                String linkedSourceIds = toJson(linkedIds);

                // Create audit entry
                AuditTrailEntry entry = AuditTrailEntry.builder()
                                .exceptionType(ExceptionType.REFUND)
                                .actorId(request.getActorId())
                                .actorRole(request.getActorRole())
                                .reason(request.getReason())
                                .authorizationLevel(request.getActorRole())
                                .policyVersion(authResult.getPolicyVersion())
                                .invoiceId(request.getInvoiceId())
                                .paymentId(request.getPaymentId())
                                .refundType(request.getRefundType())
                                .refundAmount(request.getRefundAmount())
                                .originalPaymentStatus(request.getOriginalPaymentStatus())
                                .refundMethod(authResult.getRefundMethod())
                                .linkedSourceIds(linkedSourceIds)
                                .accountingIntent(accountingIntent)
                                .accountingStatus(AccountingStatus.PENDING_POSTING)
                                .expectedAccountingOutcome(String.format("%s refund of $%.2f via %s",
                                                request.getRefundType(), request.getRefundAmount(),
                                                authResult.getRefundMethod()))
                                .sourceEventId(UUID.randomUUID())
                                .sourceDocumentId("INVOICE-" + request.getInvoiceId())
                                .build();

                entry = auditRepository.save(entry);
                log.info("Refund recorded with audit ID {}", entry.getAuditId());

                // Emit event
                eventPublisher.publishEvent(RefundCreated.builder()
                                .auditId(entry.getAuditId())
                                .invoiceId(entry.getInvoiceId())
                                .paymentId(entry.getPaymentId())
                                .refundType(entry.getRefundType())
                                .refundAmount(entry.getRefundAmount())
                                .actorId(entry.getActorId())
                                .actorRole(entry.getActorRole())
                                .authorizationLevel(entry.getAuthorizationLevel())
                                .policyVersion(entry.getPolicyVersion())
                                .reason(entry.getReason())
                                .timestamp(entry.getTimestamp())
                                .accountingIntent(entry.getAccountingIntent())
                                .accountingStatus(entry.getAccountingStatus())
                                .linkedSourceIds(entry.getLinkedSourceIds())
                                .expectedAccountingOutcome(entry.getExpectedAccountingOutcome())
                                .sourceEventId(entry.getSourceEventId())
                                .sourceDocumentId(entry.getSourceDocumentId())
                                .build());

                return mapToResponse(entry);
        }

        /**
         * Records a cancellation in the audit trail.
         * 
         * @param request the cancellation request
         * @return audit trail response
         */
        @Transactional
        public AuditTrailResponse recordCancellation(CancellationRequest request) {
                log.info("Recording cancellation of type {} for order {} invoice {}",
                                request.getCancellationType(), request.getOrderId(), request.getInvoiceId());

                // Determine accounting intent based on cancellation type
                AccountingIntent accountingIntent = request.getCancellationType() == CancellationType.ORDER_CANCELLED
                                ? AccountingIntent.REVENUE_REVERSAL
                                : AccountingIntent.PAYMENT_RECOVERY;

                // Create audit entry
                AuditTrailEntry entry = AuditTrailEntry.builder()
                                .exceptionType(ExceptionType.CANCELLATION)
                                .actorId(request.getActorId())
                                .actorRole(request.getActorRole())
                                .reason(request.getReason())
                                .authorizationLevel(request.getActorRole())
                                .policyVersion("1.0") // Default version
                                .orderId(request.getOrderId())
                                .invoiceId(request.getInvoiceId())
                                .cancellationType(request.getCancellationType())
                                .beforeSnapshot(request.getBeforeSnapshot())
                                .afterSnapshot(request.getAfterSnapshot())
                                .partialPaymentInfo(request.getPartialPaymentInfo())
                                .glReversalStatus("PENDING_REVERSAL")
                                .accountingIntent(accountingIntent)
                                .accountingStatus(AccountingStatus.PENDING_POSTING)
                                .expectedAccountingOutcome("Cancellation reversal pending accounting review")
                                .sourceEventId(UUID.randomUUID())
                                .sourceDocumentId(request.getOrderId() != null
                                                ? "ORDER-" + request.getOrderId()
                                                : "INVOICE-" + request.getInvoiceId())
                                .build();

                entry = auditRepository.save(entry);
                log.info("Cancellation recorded with audit ID {}", entry.getAuditId());

                // Emit event
                eventPublisher.publishEvent(CancellationCreated.builder()
                                .auditId(entry.getAuditId())
                                .orderId(entry.getOrderId())
                                .invoiceId(entry.getInvoiceId())
                                .cancellationType(entry.getCancellationType())
                                .actorId(entry.getActorId())
                                .actorRole(entry.getActorRole())
                                .authorizationLevel(entry.getAuthorizationLevel())
                                .policyVersion(entry.getPolicyVersion())
                                .reason(entry.getReason())
                                .timestamp(entry.getTimestamp())
                                .accountingIntent(entry.getAccountingIntent())
                                .accountingStatus(entry.getAccountingStatus())
                                .beforeSnapshot(entry.getBeforeSnapshot())
                                .afterSnapshot(entry.getAfterSnapshot())
                                .partialPaymentInfo(entry.getPartialPaymentInfo())
                                .expectedAccountingOutcome(entry.getExpectedAccountingOutcome())
                                .sourceEventId(entry.getSourceEventId())
                                .sourceDocumentId(entry.getSourceDocumentId())
                                .build());

                return mapToResponse(entry);
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

        private String toJson(Object obj) {
                try {
                        return objectMapper.writeValueAsString(obj);
                } catch (JacksonException e) {
                        log.error("Failed to serialize object to JSON", e);
                        return "{}";
                }
        }

        /**
         * Exception thrown when authorization is denied.
         */
        public static class AuthorizationException extends RuntimeException {
                public AuthorizationException(String message) {
                        super(message);
                }
        }
}
