package com.positivity.accounting.audit.service;

import com.positivity.accounting.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.audit.entity.RefundPolicyConfig;
import com.positivity.accounting.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.audit.repository.RefundPolicyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Service for initializing default policies on application startup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataInitializationService implements CommandLineRunner {
    
    private final OverridePolicyThresholdRepository policyRepository;
    private final RefundPolicyConfigRepository refundPolicyRepository;
    
    @Override
    @Transactional
    public void run(String... args) {
        initializeDefaultPolicies();
        initializeRefundPolicy();
    }
    
    private void initializeDefaultPolicies() {
        // Check if policies already exist
        if (policyRepository.count() > 0) {
            log.info("Override policies already exist, skipping initialization");
            return;
        }
        
        log.info("Initializing default override policies");
        
        // Service Writer policy
        OverridePolicyThreshold serviceWriterPolicy = OverridePolicyThreshold.builder()
            .role("SERVICE_WRITER")
            .maxAbsoluteAmount(BigDecimal.valueOf(50.00))
            .maxPercentOff(BigDecimal.valueOf(10.00))
            .version("1.0")
            .effectiveDate(Instant.now())
            .active(true)
            .build();
        policyRepository.save(serviceWriterPolicy);
        
        // Manager policy
        OverridePolicyThreshold managerPolicy = OverridePolicyThreshold.builder()
            .role("MANAGER")
            .maxAbsoluteAmount(BigDecimal.valueOf(500.00))
            .maxPercentOff(BigDecimal.valueOf(25.00))
            .version("1.0")
            .effectiveDate(Instant.now())
            .active(true)
            .build();
        policyRepository.save(managerPolicy);
        
        // Global Admin policy
        OverridePolicyThreshold adminPolicy = OverridePolicyThreshold.builder()
            .role("GLOBAL_ADMIN")
            .maxAbsoluteAmount(BigDecimal.valueOf(10000.00))
            .maxPercentOff(BigDecimal.valueOf(100.00))
            .version("1.0")
            .effectiveDate(Instant.now())
            .active(true)
            .build();
        policyRepository.save(adminPolicy);
        
        log.info("Default override policies initialized");
    }
    
    private void initializeRefundPolicy() {
        // Check if refund policy already exists
        if (refundPolicyRepository.count() > 0) {
            log.info("Refund policy already exists, skipping initialization");
            return;
        }
        
        log.info("Initializing default refund policy");
        
        RefundPolicyConfig refundPolicy = RefundPolicyConfig.builder()
            .requiresSeparateAuthorization(true)
            .settledPaymentHandling("CREDIT_MEMO")
            .unsettledPaymentHandling("REVERSAL")
            .version("1.0")
            .active(true)
            .build();
        refundPolicyRepository.save(refundPolicy);
        
        log.info("Default refund policy initialized");
    }
}
