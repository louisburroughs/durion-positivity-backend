package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.service.AccountingConfigurationService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Org-level accounting configuration backed by the
 * {@code accounting_configuration} key/value table (story B2, issue #944).
 *
 * <p>Hard-lock date semantics: postings dated strictly before the hard-lock
 * date are permanently rejected with no override; the date itself can only
 * move forward (monotonic), so the lock is irreversible. Every change is
 * audit-logged with the acting user (ADR-0018).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingConfigurationServiceImpl implements AccountingConfigurationService {

    static final String HARD_LOCK_DATE_KEY = "HARD_LOCK_DATE";

    private static final String SYSTEM = "SYSTEM";
    private static final String AUDIT_ENTITY_TYPE = "ACCOUNTING_CONFIGURATION";
    private static final String AUDIT_OPERATION_HARD_LOCK_SET = "HARD_LOCK_SET";

    private final AccountingConfigurationRepository configurationRepository;
    private final AccountingAuditLogRepository auditLogRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> getHardLockDate() {
        return configurationRepository
                .findByConfigKey(HARD_LOCK_DATE_KEY)
                .map(AccountingConfiguration::getConfigValue)
                .map(LocalDate::parse);
    }

    @Override
    @NonNull
    @Transactional
    public LocalDate setHardLockDate(@NonNull LocalDate hardLockDate, @NonNull String justification) {
        if (justification.isBlank()) {
            throw new IllegalArgumentException("A non-blank justification is required to set the hard-lock date");
        }

        // Locked read (FOR UPDATE): concurrent setters serialize on the row so
        // the monotonic-forward check below always sees the latest committed
        // date — an unlocked read-check-save would let a slower writer with an
        // earlier date regress the hard lock (last-writer-wins). The getter
        // stays on the unlocked finder.
        AccountingConfiguration config = configurationRepository
                .findWithLockByConfigKey(HARD_LOCK_DATE_KEY)
                .orElse(null);
        LocalDate currentDate = config != null ? LocalDate.parse(config.getConfigValue()) : null;

        if (currentDate != null && hardLockDate.isBefore(currentDate)) {
            throw new HardLockDateRegressionException(currentDate, hardLockDate);
        }

        if (config == null) {
            config = new AccountingConfiguration();
            config.setConfigKey(HARD_LOCK_DATE_KEY);
        }
        config.setConfigValue(hardLockDate.toString());
        AccountingConfiguration saved = configurationRepository.save(config);

        String actor = currentActor();
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE);
        auditLog.setEntityId(saved.getConfigId());
        auditLog.setOperation(AUDIT_OPERATION_HARD_LOCK_SET);
        auditLog.setUserId(actor);
        auditLog.setJustification(justification);
        auditLog.setOldValue(currentDate != null ? currentDate.toString() : null);
        auditLog.setNewValue(hardLockDate.toString());
        auditLogRepository.save(auditLog);

        log.info("Hard-lock date set to {} by {} (previous: {})", hardLockDate, actor, currentDate);
        return hardLockDate;
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
