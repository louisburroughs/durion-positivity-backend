package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.entity.RefundPolicyConfig;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.internal.audit.repository.RefundPolicyConfigRepository;
import com.positivity.tenancy.TenantIterator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service for initializing default policies on application startup.
 *
 * <p>The policies are tenant data (ADR-0062), so the run seeds each tenant of the registry in turn
 * ({@link TenantIterator#forEachActiveTenant}), opening the transaction inside the binding so the
 * connection checks out bound; a tenant that already has policies is left alone. A tenant created
 * after startup is seeded on the next start (provisioning-time seeding is plan WS8).
 */
@Service
@Slf4j
public class DataInitializationServiceImpl implements CommandLineRunner, DataInitializationService {

    private final Clock clock;
    private final OverridePolicyThresholdRepository policyRepository;
    private final RefundPolicyConfigRepository refundPolicyRepository;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;

    public DataInitializationServiceImpl(
            Clock clock,
            OverridePolicyThresholdRepository policyRepository,
            RefundPolicyConfigRepository refundPolicyRepository,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.policyRepository = policyRepository;
        this.refundPolicyRepository = refundPolicyRepository;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        tenantIterator.forEachActiveTenant(tenantId -> transaction.executeWithoutResult(status -> {
            initializeDefaultPolicies();
            initializeRefundPolicy();
        }));
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
                .effectiveDate(Instant.now(clock))
                .active(true)
                .build();
        policyRepository.save(serviceWriterPolicy);

        // Manager policy
        OverridePolicyThreshold managerPolicy = OverridePolicyThreshold.builder()
                .role("MANAGER")
                .maxAbsoluteAmount(BigDecimal.valueOf(500.00))
                .maxPercentOff(BigDecimal.valueOf(25.00))
                .version("1.0")
                .effectiveDate(Instant.now(clock))
                .active(true)
                .build();
        policyRepository.save(managerPolicy);

        // Global Admin policy
        OverridePolicyThreshold adminPolicy = OverridePolicyThreshold.builder()
                .role("GLOBAL_ADMIN")
                .maxAbsoluteAmount(BigDecimal.valueOf(10000.00))
                .maxPercentOff(BigDecimal.valueOf(100.00))
                .version("1.0")
                .effectiveDate(Instant.now(clock))
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
