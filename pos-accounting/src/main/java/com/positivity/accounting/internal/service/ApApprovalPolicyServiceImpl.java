package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.IsoCurrencyCodes;
import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AP approval policy's reads and writes (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §5.5, §8.2; AW4-AW6,
 * AW33), on the bank-reconciliation policy precedent ({@code AccountingConfigurationServiceImpl
 * .setBankReconciliationPolicy}): one {@code accounting_configuration} row per setting, locked {@code FOR UPDATE}
 * while it changes, and one {@value #AUDIT_OPERATION} audit row per setting whose effective value changes.
 *
 * <p><b>The audit row</b> is the history: entity type {@value #AUDIT_ENTITY_TYPE} (the setting's row), the actor, the
 * justification, the effective value before as {@code old_value}, and as {@code new_value} {@code
 * setting=<KEY>;value=<NEW>;roles=<ROLE,...>;requestId=<UUID>}. The request id makes the PUT idempotent: a replay finds
 * it recorded and writes nothing.
 *
 * <p>A new value applies from the next decision, read or outlook; nothing posts, recomputes or back-dates (AW11), and
 * approved bills are never re-evaluated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApApprovalPolicyServiceImpl implements ApApprovalPolicyService {

    static final String AUDIT_ENTITY_TYPE = "ACCOUNTING_CONFIGURATION";
    static final String AUDIT_OPERATION = "AP_APPROVAL_POLICY_SET";

    private static final String SETTING = "setting";
    private static final String VALUE = "value";
    private static final String ROLES = "roles";
    private static final String REQUEST_ID = "requestId";

    private final Clock clock;
    private final ApApprovalPolicy policy;
    private final AccountingConfigurationRepository configurationRepository;
    private final AccountingAuditLogRepository auditLogRepository;
    private final FunctionalCurrency functionalCurrency;

    @Override
    @Transactional(readOnly = true)
    public @NonNull ApApprovalPolicyResponse get(int historyPage, int historySize) {
        int page = Math.max(historyPage, 0);
        int size = historySize > 0 && historySize <= MAX_HISTORY_SIZE ? historySize : DEFAULT_HISTORY_SIZE;
        ApApprovalPolicy.Settings settings = policy.settings();
        Page<AccountingAuditLog> rows = auditLogRepository.findByOperation(
                AUDIT_OPERATION,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("auditLogId"))));
        return new ApApprovalPolicyResponse(
                settings.clerkApprovalLimit(),
                settings.autoApprovalLimit(),
                functionalCurrency.code(),
                settings.allowCreatorApproval(),
                settings.allowApproverPayment(),
                settings.defaultTerms(),
                Instant.now(clock),
                rows.getContent().stream()
                        .map(ApApprovalPolicyServiceImpl::historyRow)
                        .toList(),
                page,
                size,
                rows.getTotalElements());
    }

    @Override
    @Transactional
    public @NonNull ApApprovalPolicyResponse set(@NonNull ApApprovalPolicyRequest request) {
        String actor = VendorBillDecisions.actor();
        String justification = VendorBillDecisions.required(request.justification(), "justification");
        Map<String, String> requested = validated(request);

        // Every policy row locked, in key order, before anything is read: a concurrent PUT waits, and so does a
        // decision reading the policy share-locked.
        Map<String, AccountingConfiguration> rows = new HashMap<>();
        for (String key : ApApprovalPolicy.KEYS.stream().sorted().toList()) {
            configurationRepository.findWithLockByConfigKey(key).ifPresent(row -> rows.put(key, row));
        }
        UUID requestId = Objects.requireNonNull(request.requestId());
        if (auditLogRepository.existsByOperationAndNewValueContaining(
                AUDIT_OPERATION, ";" + REQUEST_ID + "=" + requestId)) {
            log.info("AP approval policy PUT {} replayed; nothing written", requestId);
            return get(0, DEFAULT_HISTORY_SIZE);
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (String key : ApApprovalPolicy.KEYS) {
            AccountingConfiguration row = rows.get(key);
            current.put(key, effective(key, row == null ? null : row.getConfigValue()));
        }
        requireAutoWithinClerk(
                new BigDecimal(requested.getOrDefault(
                        ApApprovalPolicy.CLERK_APPROVAL_LIMIT, current.get(ApApprovalPolicy.CLERK_APPROVAL_LIMIT))),
                new BigDecimal(requested.getOrDefault(
                        ApApprovalPolicy.AUTO_APPROVAL_LIMIT, current.get(ApApprovalPolicy.AUTO_APPROVAL_LIMIT))));

        String roles = String.join(",", VendorBillDecisions.callerRoles());
        int changed = 0;
        for (Map.Entry<String, String> setting : requested.entrySet()) {
            String key = setting.getKey();
            String oldValue = current.get(key);
            if (Objects.equals(oldValue, setting.getValue())) {
                continue;
            }
            AccountingConfiguration row = rows.get(key);
            if (row == null) {
                row = new AccountingConfiguration();
                row.setConfigKey(key);
            }
            row.setConfigValue(setting.getValue());
            UUID entityId = configurationRepository.save(row).getConfigId();
            AccountingAuditLog audit = new AccountingAuditLog();
            audit.setEntityType(AUDIT_ENTITY_TYPE);
            audit.setEntityId(entityId);
            audit.setOperation(AUDIT_OPERATION);
            audit.setUserId(actor);
            audit.setJustification(justification);
            audit.setOldValue(oldValue);
            audit.setNewValue(SETTING + "=" + key + ";" + VALUE + "=" + setting.getValue() + ";" + ROLES + "=" + roles
                    + ";" + REQUEST_ID + "=" + requestId);
            auditLogRepository.save(audit);
            changed++;
        }
        log.info("AP approval policy set by {}: {} setting(s) changed (requestId {})", actor, changed, requestId);
        return get(0, DEFAULT_HISTORY_SIZE);
    }

    /**
     * The request's settings in their stored form, keyed by setting, each checked: amounts at least 0, in the
     * functional currency (ADR-0067: currencyCode required with a limit, the functional currency, 422 {@code
     * CURRENCY_NOT_SUPPORTED} otherwise; no finer than its minor unit, 422 {@code AMOUNT_PRECISION_EXCEEDS_CURRENCY}),
     * and the terms in the vocabulary. Every field error is answered at once: 400 {@code VALIDATION_ERROR} with {@code
     * fieldErrors}.
     */
    private Map<String, String> validated(ApApprovalPolicyRequest request) {
        List<VendorBillException.FieldError> errors = new ArrayList<>();
        if (request.requestId() == null) {
            errors.add(new VendorBillException.FieldError(REQUEST_ID, "is required: a UUID generated once per change"));
        }
        boolean limitGiven = request.clerkApprovalLimit() != null || request.autoApprovalLimit() != null;
        nonNegative(request.clerkApprovalLimit(), "clerkApprovalLimit", errors);
        nonNegative(request.autoApprovalLimit(), "autoApprovalLimit", errors);
        if (limitGiven && !IsoCurrencyCodes.isIso(request.currencyCode())) {
            errors.add(new VendorBillException.FieldError(
                    "currencyCode", "is required with a limit and must be an ISO 4217 currency code"));
        }
        if (request.defaultTerms() != null && !CashAndPayablesSettings.isTerms(request.defaultTerms())) {
            errors.add(new VendorBillException.FieldError(
                    "defaultTerms", "must be DUE_ON_RECEIPT or NET<n>, n an integer from 1 to 120, upper case"));
        }
        if (!errors.isEmpty()) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "The AP approval policy request is not valid: "
                            + String.join(
                                    ", ",
                                    errors.stream()
                                            .map(VendorBillException.FieldError::field)
                                            .toList()),
                    errors,
                    null);
        }
        if (limitGiven && !functionalCurrency.code().equals(request.currencyCode())) {
            throw new CurrencyNotSupportedException("currencyCode " + request.currencyCode()
                    + " is not the functional currency " + functionalCurrency.code() + "; the limits are kept in it");
        }
        Map<String, @Nullable BigDecimal> amounts = new LinkedHashMap<>();
        amounts.put("clerkApprovalLimit", request.clerkApprovalLimit());
        amounts.put("autoApprovalLimit", request.autoApprovalLimit());
        functionalCurrency.requireMinorUnits(amounts);

        Map<String, String> requested = new LinkedHashMap<>();
        if (request.clerkApprovalLimit() != null) {
            requested.put(
                    ApApprovalPolicy.CLERK_APPROVAL_LIMIT,
                    policy.scaled(request.clerkApprovalLimit()).toPlainString());
        }
        if (request.autoApprovalLimit() != null) {
            requested.put(
                    ApApprovalPolicy.AUTO_APPROVAL_LIMIT,
                    policy.scaled(request.autoApprovalLimit()).toPlainString());
        }
        if (request.allowCreatorApproval() != null) {
            requested.put(
                    ApApprovalPolicy.ALLOW_CREATOR_APPROVAL,
                    request.allowCreatorApproval().toString());
        }
        if (request.allowApproverPayment() != null) {
            requested.put(
                    ApApprovalPolicy.ALLOW_APPROVER_PAYMENT,
                    request.allowApproverPayment().toString());
        }
        if (request.defaultTerms() != null) {
            requested.put(ApApprovalPolicy.DEFAULT_TERMS, request.defaultTerms());
        }
        return requested;
    }

    private static void nonNegative(
            @Nullable BigDecimal amount, String field, List<VendorBillException.FieldError> errors) {
        if (amount != null && amount.signum() < 0) {
            errors.add(new VendorBillException.FieldError(field, "must be at least 0"));
        }
    }

    /** The automatic limit never exceeds the clerk limit (item 1): 400 {@code VALIDATION_ERROR} otherwise. */
    private static void requireAutoWithinClerk(BigDecimal clerk, BigDecimal auto) {
        if (auto.compareTo(clerk) > 0) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "autoApprovalLimit " + auto.toPlainString() + " exceeds clerkApprovalLimit "
                            + clerk.toPlainString(),
                    List.of(new VendorBillException.FieldError(
                            "autoApprovalLimit", "must not exceed clerkApprovalLimit (" + clerk.toPlainString() + ")")),
                    null);
        }
    }

    /** The canonical effective value of a stored setting (its default when absent or unreadable), as the PUT writes. */
    private String effective(String key, @Nullable String stored) {
        return switch (key) {
            case ApApprovalPolicy.CLERK_APPROVAL_LIMIT, ApApprovalPolicy.AUTO_APPROVAL_LIMIT ->
                policy.scaled(ApApprovalPolicy.parseAmount(key, stored)).toPlainString();
            case ApApprovalPolicy.ALLOW_CREATOR_APPROVAL, ApApprovalPolicy.ALLOW_APPROVER_PAYMENT ->
                Boolean.toString(ApApprovalPolicy.parseSwitch(key, stored));
            case ApApprovalPolicy.DEFAULT_TERMS -> CashAndPayablesSettings.parseTerms(stored);
            default -> throw new IllegalStateException("Not an AP approval policy key: " + key);
        };
    }

    /** One history row from its audit row; see the class comment for the {@code new_value} form. */
    static ApApprovalPolicyResponse.HistoryRow historyRow(AccountingAuditLog row) {
        Map<String, String> fields = new HashMap<>();
        if (row.getNewValue() != null) {
            for (String part : row.getNewValue().split(";")) {
                int equals = part.indexOf('=');
                if (equals > 0) {
                    fields.put(part.substring(0, equals), part.substring(equals + 1));
                }
            }
        }
        String roles = fields.getOrDefault(ROLES, "");
        return new ApApprovalPolicyResponse.HistoryRow(
                row.getTimestamp(),
                row.getUserId(),
                roles.isEmpty() ? List.of() : Arrays.asList(roles.split(",")),
                fields.getOrDefault(SETTING, ""),
                row.getOldValue(),
                fields.getOrDefault(VALUE, ""),
                row.getJustification());
    }
}
