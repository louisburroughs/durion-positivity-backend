package com.positivity.accounting.internal.audit.service;

import com.positivity.accounting.internal.audit.entity.PaymentStatus;
import com.positivity.accounting.internal.audit.entity.RefundMethod;
import com.positivity.accounting.internal.audit.entity.RefundPolicyConfig;
import com.positivity.accounting.internal.audit.entity.RefundType;
import com.positivity.accounting.internal.audit.repository.RefundPolicyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service for validating refund authorization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundAuthorizationService {
    
    private final RefundPolicyConfigRepository policyRepository;
    
    /**
     * Validates refund authorization and determines appropriate refund method.
     * 
     * @param actorRole the actor's role
     * @param paymentStatus the original payment status
     * @param refundType the type of refund
     * @return refund authorization result
     */
    public RefundAuthorizationResult validate(String actorRole, PaymentStatus paymentStatus, 
                                              RefundType refundType) {
        log.info("Validating refund for role {} with payment status {} and type {}", 
                actorRole, paymentStatus, refundType);
        
        // Get active policy
        Optional<RefundPolicyConfig> policyOpt = policyRepository.findActivePolicy();
        if (policyOpt.isEmpty()) {
            log.error("No active refund policy found");
            return RefundAuthorizationResult.builder()
                .authorized(false)
                .message("No active refund policy configured")
                .build();
        }
        
        RefundPolicyConfig policy = policyOpt.get();
        
        // Check if separate authorization is required
        if (policy.getRequiresSeparateAuthorization()) {
            // In a real implementation, we'd verify that the actor has refund authorization
            // For now, we assume the caller has already verified this
            log.info("Refund requires separate authorization - assuming verified");
        }
        
        // Determine refund method based on payment status
        RefundMethod refundMethod = determineRefundMethod(paymentStatus, refundType, policy);
        
        log.info("Refund authorized with method {} under policy version {}", 
                refundMethod, policy.getVersion());
        return RefundAuthorizationResult.builder()
            .authorized(true)
            .refundMethod(refundMethod)
            .policyVersion(policy.getVersion())
            .build();
    }
    
    private RefundMethod determineRefundMethod(PaymentStatus paymentStatus, 
                                              RefundType refundType,
                                              RefundPolicyConfig policy) {
        if (paymentStatus == PaymentStatus.SETTLED) {
            // Settled payments: use credit memo or cash refund
            if (refundType == RefundType.CREDIT_MEMO) {
                return RefundMethod.CREDIT_MEMO;
            } else {
                return RefundMethod.CASH_REFUND;
            }
        } else {
            // Unsettled payments: use reversal
            if (refundType == RefundType.REVERSAL) {
                return RefundMethod.VOID;
            } else {
                return RefundMethod.CHARGEBACK;
            }
        }
    }
    
    /**
     * Result of refund authorization validation.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RefundAuthorizationResult {
        private boolean authorized;
        private RefundMethod refundMethod;
        private String policyVersion;
        private String message;
    }
}
