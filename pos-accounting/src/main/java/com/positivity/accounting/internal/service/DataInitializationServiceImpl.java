package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.entity.RefundPolicyConfig;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.internal.audit.repository.RefundPolicyConfigRepository;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a tenant's default override thresholds, refund policy and accounting time zone.
 *
 * <p>The policies are tenant data (ADR-0062). They are created when the tenant is provisioned
 * ({@link AccountingTenantProvisioner}: on {@code tenant.created}, and for registry tenants at
 * startup), inside the provisioning transaction; a tenant that already has policies of a kind is
 * left alone.
 *
 * <p>The accounting time zone (#2558) starts as {@code UTC}, the zone the module dated everything in before the
 * setting existed; an administrator sets the legal entity's real zone before the first period close. A tenant that
 * has the setting keeps it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataInitializationServiceImpl implements DataInitializationService {

    private final Clock clock;
    private final OverridePolicyThresholdRepository policyRepository;
    private final RefundPolicyConfigRepository refundPolicyRepository;
    private final AccountingConfigurationRepository configurationRepository;

    /** The accounting time zone a new tenant starts with (#2558). */
    static final String DEFAULT_ACCOUNTING_TIME_ZONE = "UTC";

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int seedPolicyDefaults() {
        return initializeDefaultPolicies() + initializeRefundPolicy() + initializeAccountingTimeZone();
    }

    private int initializeAccountingTimeZone() {
        if (configurationRepository
                .findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY)
                .isPresent()) {
            return 0;
        }
        AccountingConfiguration zone = new AccountingConfiguration();
        zone.setConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);
        zone.setConfigValue(DEFAULT_ACCOUNTING_TIME_ZONE);
        configurationRepository.save(zone);
        log.info("Accounting time zone initialized to {}", DEFAULT_ACCOUNTING_TIME_ZONE);
        return 1;
    }

    private int initializeDefaultPolicies() {
        // Check if policies already exist
        if (policyRepository.count() > 0) {
            log.debug("Override policies already exist, skipping initialization");
            return 0;
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
        return 3;
    }

    private int initializeRefundPolicy() {
        // Check if refund policy already exists
        if (refundPolicyRepository.count() > 0) {
            log.debug("Refund policy already exists, skipping initialization");
            return 0;
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
        return 1;
    }
}
