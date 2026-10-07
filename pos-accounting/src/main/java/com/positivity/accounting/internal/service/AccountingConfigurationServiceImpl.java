package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingTimeZoneLockedException;
import com.positivity.accounting.internal.exception.HardLockDateRegressionException;
import com.positivity.accounting.internal.exception.InvalidAccountingTimeZoneException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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

    static final String AUDIT_OPERATION_BANK_REC_POLICY_SET = "BANK_REC_POLICY_SET";

    static final String AUDIT_OPERATION_TIME_ZONE_SET = "ACCOUNTING_TIME_ZONE_SET";

    /** The one fixed zone a tenant may keep: the provisioning seed (#2558). */
    private static final String UTC = "UTC";

    /** The longest policy justification kept (the request's documented maximum). */
    private static final int MAX_JUSTIFICATION = 1000;

    private final AccountingConfigurationRepository configurationRepository;
    private final AccountingAuditLogRepository auditLogRepository;
    private final BankRecPolicy bankRecPolicy;
    private final FunctionalCurrency functionalCurrency;
    private final AccountingPeriodRepository periodRepository;

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
            throw new InvalidRequestParameterException(
                    "A non-blank justification is required to set the hard-lock date");
        }

        // The calendar lock first (#2558): a hard lock fixes the accounting time zone, so it serializes on the zone row
        // with a zone change and a period close, always taken before any other row these three lock.
        configurationRepository.findWithLockByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);

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

    @Override
    @NonNull
    @Transactional
    public String setAccountingTimeZone(@NonNull String timeZone) {
        ZoneId requested = validTimeZone(timeZone);
        // Locked read of the calendar lock (#2558): a zone change, a period close and a hard-lock change all take this
        // row FOR UPDATE first, so a close or hard lock that commits first is seen by the checks below (READ COMMITTED
        // re-reads per statement), and one that waits sees the new zone.
        AccountingConfiguration row = configurationRepository
                .findWithLockByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY)
                .orElse(null);
        String current = row != null ? row.getConfigValue() : null;
        if (requested.getId().equals(current)) {
            return current;
        }
        // A closed (or reopened) period, or a hard lock, fixes the calendar: a new zone would move the boundary of a
        // month already cut (ruling #2558: a change never re-cuts history).
        if (periodRepository.existsByStatusOrClosedAtIsNotNull(AccountingPeriodStatus.CLOSED)
                || getHardLockDate().isPresent()) {
            throw new AccountingTimeZoneLockedException(String.valueOf(current), requested.getId());
        }
        if (row == null) {
            row = new AccountingConfiguration();
            row.setConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);
        }
        row.setConfigValue(requested.getId());
        AccountingConfiguration saved = configurationRepository.save(row);

        String actor = currentActor();
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE);
        auditLog.setEntityId(saved.getConfigId());
        auditLog.setOperation(AUDIT_OPERATION_TIME_ZONE_SET);
        auditLog.setUserId(actor);
        auditLog.setOldValue(current);
        auditLog.setNewValue(requested.getId());
        auditLogRepository.save(auditLog);

        log.info("Accounting time zone set to {} by {} (previous: {})", requested.getId(), actor, current);
        return requested.getId();
    }

    /**
     * An IANA region id: known to the zone database, not a {@code SystemV/*} id, and not a fixed offset. The one
     * fixed zone accepted is {@code UTC} itself, the seed; its aliases ({@code GMT}, {@code Etc/UTC}, {@code
     * Etc/GMT}, ...) and every other fixed offset ({@code +05:00}, {@code UTC+05:00}, {@code Etc/GMT+5}) are refused.
     */
    static ZoneId validTimeZone(String timeZone) {
        String id = timeZone.trim();
        if (id.isEmpty()) {
            throw new InvalidAccountingTimeZoneException(timeZone, "a zone id is required");
        }
        if (id.startsWith("SystemV/")) {
            throw new InvalidAccountingTimeZoneException(id, "SystemV zones are not IANA region ids");
        }
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new InvalidAccountingTimeZoneException(
                    id, "not an IANA region id such as America/Chicago (fixed offsets are not accepted)");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new InvalidAccountingTimeZoneException(id, e.getMessage());
        }
        ZoneRules rules = zone.getRules();
        if (rules.isFixedOffset() && !UTC.equals(id)) {
            throw new InvalidAccountingTimeZoneException(id, "a fixed offset is not an accounting calendar zone");
        }
        return zone;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public BankReconciliationPolicyResponse getBankReconciliationPolicy() {
        BankRecPolicy.Settings settings = bankRecPolicy.settings();
        Optional<AccountingAuditLog> latest =
                auditLogRepository.findFirstByOperationOrderByTimestampDesc(AUDIT_OPERATION_BANK_REC_POLICY_SET);
        return BankReconciliationPolicyResponse.builder()
                .closePolicy(settings.closePolicy())
                .closeScope(settings.closeScope())
                .closeCoverageLagDays(settings.closeCoverageLagDays())
                .allowSelfApproval(settings.allowSelfApproval())
                .otherApprovalThreshold(settings.otherApprovalThreshold())
                .currency(functionalCurrency.code())
                .updatedAt(latest.map(AccountingAuditLog::getTimestamp).orElse(null))
                .updatedBy(latest.map(AccountingAuditLog::getUserId).orElse(null))
                .build();
    }

    @Override
    @NonNull
    @Transactional
    public BankReconciliationPolicyResponse setBankReconciliationPolicy(
            @NonNull BankReconciliationPolicyRequest request) {
        String justification = Justification.required(request.getJustification(), "justification");
        if (justification.length() > MAX_JUSTIFICATION) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "justification must not exceed " + MAX_JUSTIFICATION + " characters",
                    "justification",
                    "at most " + MAX_JUSTIFICATION + " characters");
        }
        if (!request.isOtherApprovalThresholdPresent()) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "otherApprovalThreshold is required; send null to clear it",
                    "otherApprovalThreshold",
                    "is required");
        }
        Map<String, @Nullable String> requested = new LinkedHashMap<>();
        requested.put(
                BankRecPolicy.CLOSE_POLICY,
                required(request.getClosePolicy(), "closePolicy").name());
        requested.put(
                BankRecPolicy.CLOSE_SCOPE,
                required(request.getCloseScope(), "closeScope").name());
        int lag = required(request.getCloseCoverageLagDays(), "closeCoverageLagDays");
        if (lag < 0) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "closeCoverageLagDays must not be negative",
                    "closeCoverageLagDays",
                    "must not be negative");
        }
        requested.put(BankRecPolicy.CLOSE_COVERAGE_LAG_DAYS, Integer.toString(lag));
        requested.put(
                BankRecPolicy.ALLOW_SELF_APPROVAL,
                required(request.getAllowSelfApproval(), "allowSelfApproval").toString());
        requested.put(BankRecPolicy.OTHER_APPROVAL_THRESHOLD, threshold(request.getOtherApprovalThreshold()));

        String actor = currentActor();
        int changed = 0;
        for (Map.Entry<String, @Nullable String> setting : requested.entrySet()) {
            if (applySetting(setting.getKey(), setting.getValue(), justification, actor)) {
                changed++;
            }
        }
        log.info("Bank reconciliation policy set by {}: {} setting(s) changed", actor, changed);
        return getBankReconciliationPolicy();
    }

    /** Writes and audits one setting when its effective value changes; returns whether it did. */
    private boolean applySetting(String key, @Nullable String newValue, String justification, String actor) {
        AccountingConfiguration row =
                configurationRepository.findWithLockByConfigKey(key).orElse(null);
        String oldValue = effective(key, row != null ? row.getConfigValue() : null);
        if (Objects.equals(oldValue, newValue)) {
            return false;
        }
        UUID entityId;
        if (newValue == null) {
            // Only the threshold is nullable: clearing it removes the row (no row = unset, §4.7).
            entityId = row.getConfigId();
            configurationRepository.delete(row);
        } else {
            if (row == null) {
                row = new AccountingConfiguration();
                row.setConfigKey(key);
            }
            row.setConfigValue(newValue);
            entityId = configurationRepository.save(row).getConfigId();
        }
        AccountingAuditLog auditLog = new AccountingAuditLog();
        auditLog.setEntityType(AUDIT_ENTITY_TYPE);
        auditLog.setEntityId(entityId);
        auditLog.setOperation(AUDIT_OPERATION_BANK_REC_POLICY_SET);
        auditLog.setUserId(actor);
        auditLog.setJustification(justification);
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLogRepository.save(auditLog);
        return true;
    }

    /** The canonical effective value of a stored setting (its default when absent), as the PUT would write it. */
    private @Nullable String effective(String key, @Nullable String stored) {
        return switch (key) {
            case BankRecPolicy.CLOSE_POLICY ->
                BankRecPolicy.parseClosePolicy(stored).name();
            case BankRecPolicy.CLOSE_SCOPE ->
                BankRecPolicy.parseCloseScope(stored).name();
            case BankRecPolicy.CLOSE_COVERAGE_LAG_DAYS -> Integer.toString(BankRecPolicy.parseLagDays(stored));
            case BankRecPolicy.ALLOW_SELF_APPROVAL -> Boolean.toString(BankRecPolicy.parseAllowSelfApproval(stored));
            case BankRecPolicy.OTHER_APPROVAL_THRESHOLD ->
                BankRecPolicy.parseThreshold(stored).map(this::scaled).orElse(null);
            default -> throw new IllegalStateException("Not a bank reconciliation policy key: " + key);
        };
    }

    /**
     * The threshold as stored: in the functional currency's minor unit; finer precision is refused with 422
     * {@code AMOUNT_PRECISION_EXCEEDS_CURRENCY} rather than rounded (ADR-0067 PC-6).
     */
    private @Nullable String threshold(@Nullable BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "otherApprovalThreshold must not be negative",
                    "otherApprovalThreshold",
                    "must not be negative");
        }
        functionalCurrency.requireMinorUnit(amount, "otherApprovalThreshold");
        return scaled(amount);
    }

    private String scaled(BigDecimal amount) {
        return amount.setScale(functionalCurrency.fractionDigits(), RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static <T> T required(@Nullable T value, String field) {
        if (value == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, field + " is required", field, "is required");
        }
        return value;
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}
