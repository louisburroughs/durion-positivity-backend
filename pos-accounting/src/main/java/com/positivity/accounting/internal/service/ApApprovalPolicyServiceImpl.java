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
 * AW33; rulings 6063520413), on the bank-reconciliation policy precedent: one {@code accounting_configuration} row per
 * setting, written only when its effective value changes, each change one {@value #AUDIT_OPERATION} audit row.
 *
 * <p><b>The audit rows are the history.</b> Entity type {@value #AUDIT_ENTITY_TYPE} (the setting's row), the actor,
 * the justification, the effective value before as {@code old_value}, and as {@code new_value} {@code
 * setting=<KEY>;value=<NEW>;roles=<ROLE,...>[;cause=<KEY>];requestId=<UUID>}, every key and value percent-encoded for
 * {@code %}, {@code ;}, {@code =} and {@code ,}, read first-occurrence-wins ({@link #encode}, {@link #decode}).
 *
 * <p><b>Idempotent on {@code requestId}.</b> Every PUT that passes validation also writes one {@value
 * #REQUEST_OPERATION} row whose entity id is the request id, a no-op PUT included; a replay finds it and writes
 * nothing. The history lists only {@value #AUDIT_OPERATION} rows.
 *
 * <p><b>Lowering the clerk limit</b> below the stored automatic limit, with no automatic limit sent, lowers the
 * automatic limit to it, on its own row tagged {@code cause=AP_CLERK_APPROVAL_LIMIT} (ruling item 2); an automatic
 * limit sent above the clerk limit is 400.
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
    private static final String CAUSE = "cause";

    /**
     * One row per PUT that passed validation, a no-op included: the request id as its entity id, so a replay is found
     * exactly (#2622 review M2). The history ({@value #AUDIT_OPERATION} rows) does not list it.
     */
    static final String REQUEST_OPERATION = "AP_APPROVAL_POLICY_REQUEST";

    /** The largest number of integer digits a limit may have (#2622 review M3): 13, as {@code numeric(19,4)} money. */
    static final int MAX_INTEGER_DIGITS = 13;

    private final Clock clock;
    private final ApApprovalPolicy policy;
    private final AccountingConfigurationRepository configurationRepository;
    private final AccountingAuditLogRepository auditLogRepository;
    private final FunctionalCurrency functionalCurrency;
    private final ApApprovalPolicyLock policyLock;
    private final ActorDisplayNames actorNames;

    @Override
    @Transactional(readOnly = true)
    public @NonNull ApApprovalPolicyResponse get(int historyPage, int historySize) {
        int page = Math.max(historyPage, 0);
        int size = historySize <= 0 ? DEFAULT_HISTORY_SIZE : Math.min(historySize, MAX_HISTORY_SIZE);
        ApApprovalPolicy.Settings settings = policy.settings();
        Page<AccountingAuditLog> rows = auditLogRepository.findByOperation(
                AUDIT_OPERATION,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("auditLogId"))));
        // One query for the page's actors (#2670), never one per row.
        Map<String, String> names = actorNames.namesOf(
                rows.getContent().stream().map(AccountingAuditLog::getUserId).toList());
        return new ApApprovalPolicyResponse(
                settings.clerkApprovalLimit(),
                settings.autoApprovalLimit(),
                functionalCurrency.code(),
                settings.allowCreatorApproval(),
                settings.allowApproverPayment(),
                settings.defaultTerms(),
                Instant.now(clock),
                rows.getContent().stream().map(row -> historyRow(row, names)).toList(),
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

        // The tenant's policy lock first: on a fresh tenant there is no row to lock, and two first PUTs would race on
        // the inserts. Then every policy row, in key order, so a decision reading them share-locked waits too.
        policyLock.lock();
        Map<String, AccountingConfiguration> rows = new HashMap<>();
        for (String key : ApApprovalPolicy.KEYS.stream().sorted().toList()) {
            configurationRepository.findWithLockByConfigKey(key).ifPresent(row -> rows.put(key, row));
        }
        UUID requestId = Objects.requireNonNull(request.requestId());
        // The replay check: one marker row per request id, written by every PUT that got this far, a no-op included
        // (#2622 review M2), and matched exactly on its entity id, never on text a caller could shape.
        if (auditLogRepository.existsByOperationAndEntityId(REQUEST_OPERATION, requestId)) {
            log.info("AP approval policy PUT {} replayed; nothing written", requestId);
            return get(0, DEFAULT_HISTORY_SIZE);
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (String key : ApApprovalPolicy.KEYS) {
            AccountingConfiguration row = rows.get(key);
            current.put(key, effective(key, row == null ? null : row.getConfigValue()));
        }
        BigDecimal clerk = new BigDecimal(requested.getOrDefault(
                ApApprovalPolicy.CLERK_APPROVAL_LIMIT, current.get(ApApprovalPolicy.CLERK_APPROVAL_LIMIT)));
        Map<String, String> causes = new HashMap<>();
        if (requested.containsKey(ApApprovalPolicy.AUTO_APPROVAL_LIMIT)) {
            // An explicit automatic limit is never changed behind the caller's back (ruling 6063520413 item 2).
            requireAutoWithinClerk(clerk, new BigDecimal(requested.get(ApApprovalPolicy.AUTO_APPROVAL_LIMIT)));
        } else if (new BigDecimal(current.get(ApApprovalPolicy.AUTO_APPROVAL_LIMIT)).compareTo(clerk) > 0) {
            // Ruling item 2 (c): a clerk limit below the stored automatic limit lowers it with it, its own audited
            // row; a clerk limit of 0 turns automatic approval off. A stored state above the clerk limit is repaired.
            requested.put(
                    ApApprovalPolicy.AUTO_APPROVAL_LIMIT, policy.scaled(clerk).toPlainString());
            causes.put(ApApprovalPolicy.AUTO_APPROVAL_LIMIT, ApApprovalPolicy.CLERK_APPROVAL_LIMIT);
        }

        String roles = String.join(
                ",",
                VendorBillDecisions.callerRoles().stream()
                        .map(ApApprovalPolicyServiceImpl::escape)
                        .toList());
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
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(SETTING, key);
            fields.put(VALUE, setting.getValue());
            fields.put(ROLES, roles);
            if (causes.containsKey(key)) {
                fields.put(CAUSE, causes.get(key));
            }
            fields.put(REQUEST_ID, requestId.toString());
            auditLogRepository.save(
                    auditRow(AUDIT_OPERATION, entityId, actor, justification, oldValue, encode(fields)));
            changed++;
        }
        auditLogRepository.save(auditRow(
                REQUEST_OPERATION,
                requestId,
                actor,
                justification,
                null,
                encode(Map.of(REQUEST_ID, requestId.toString(), "changed", Integer.toString(changed)))));
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
        bounded(request.clerkApprovalLimit(), "clerkApprovalLimit", errors);
        bounded(request.autoApprovalLimit(), "autoApprovalLimit", errors);
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

    /**
     * A limit is at least 0 and has at most {@value #MAX_INTEGER_DIGITS} integer digits, checked on the number as sent,
     * before it is scaled or printed (#2622 review M3: {@code 1E+100000000} is refused, never expanded).
     */
    private static void bounded(
            @Nullable BigDecimal amount, String field, List<VendorBillException.FieldError> errors) {
        if (amount == null) {
            return;
        }
        if (amount.signum() < 0) {
            errors.add(new VendorBillException.FieldError(field, "must be at least 0"));
        } else if ((long) amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            errors.add(new VendorBillException.FieldError(
                    field, "must have at most " + MAX_INTEGER_DIGITS + " digits before the decimal point"));
        }
    }

    private static AccountingAuditLog auditRow(
            String operation,
            UUID entityId,
            String actor,
            String justification,
            @Nullable String oldValue,
            String newValue) {
        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(AUDIT_ENTITY_TYPE);
        audit.setEntityId(entityId);
        audit.setOperation(operation);
        audit.setUserId(actor);
        audit.setJustification(justification);
        audit.setOldValue(oldValue);
        audit.setNewValue(newValue);
        return audit;
    }

    /**
     * {@code key=value;...}, each key and value percent-encoded for {@code %}, {@code ;}, {@code =} and {@code ,}, so
     * no value, a role name included, can add or shadow a field (#2622 review M1).
     */
    static String encode(Map<String, String> fields) {
        StringBuilder text = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!text.isEmpty()) {
                text.append(';');
            }
            text.append(escape(key)).append('=').append(escape(value));
        });
        return text.toString();
    }

    /** The fields of {@link #encode}; the first occurrence of a key wins. Rows without escapes read as they are. */
    static Map<String, String> decode(@Nullable String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text == null) {
            return fields;
        }
        for (String part : text.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                fields.putIfAbsent(unescape(part.substring(0, equals)), unescape(part.substring(equals + 1)));
            }
        }
        return fields;
    }

    private static String escape(String value) {
        return value.replace("%", "%25").replace(";", "%3B").replace("=", "%3D").replace(",", "%2C");
    }

    private static String unescape(String value) {
        return value.replace("%2C", ",").replace("%3D", "=").replace("%3B", ";").replace("%25", "%");
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

    /**
     * One history row from its audit row; see the class comment for the {@code new_value} form. {@code names} holds
     * the page's resolved display names (#2670).
     */
    static ApApprovalPolicyResponse.HistoryRow historyRow(AccountingAuditLog row, Map<String, String> names) {
        Map<String, String> fields = decode(row.getNewValue());
        String roles = fields.getOrDefault(ROLES, "");
        return new ApApprovalPolicyResponse.HistoryRow(
                row.getTimestamp(),
                row.getUserId(),
                ActorDisplayNames.nameOf(names, row.getUserId()),
                roles.isEmpty()
                        ? List.of()
                        : Arrays.stream(roles.split(","))
                                .map(ApApprovalPolicyServiceImpl::unescape)
                                .toList(),
                fields.getOrDefault(SETTING, ""),
                row.getOldValue(),
                fields.getOrDefault(VALUE, ""),
                row.getJustification());
    }
}
