package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorApSettingsResponse;
import com.positivity.accounting.internal.dto.VendorRemitToConfirmationRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AP vendor reads and commands (Issue #816; CAP:550 S24, #2517). See {@link VendorDirectoryService}.
 *
 * <p><b>Audit.</b> Entity type {@value #AUDIT_ENTITY_TYPE}, entity id the vendor id: {@value #AUDIT_REMIT_TO_CONFIRM}
 * (the version, the justification) and one {@value #AUDIT_SETTINGS_SET} row per default that changed, old to new, with
 * the {@code requestId}. A settings PUT that passes validation also writes one {@value #AUDIT_SETTINGS_REQUEST} row
 * whose entity id is the request id, so a replay finds it and writes nothing (the AP approval policy's pattern).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorDirectoryServiceImpl implements VendorDirectoryService {

    /** Server-side cap on typeahead result size. */
    public static final int MAX_RESULTS = 100;

    static final String AUDIT_ENTITY_TYPE = "VENDOR";
    static final String AUDIT_REMIT_TO_CONFIRM = "REMIT_TO_CONFIRM";
    static final String AUDIT_SETTINGS_SET = "AP_VENDOR_SETTINGS_SET";
    static final String AUDIT_SETTINGS_REQUEST = "AP_VENDOR_SETTINGS_REQUEST";

    private static final int DEFAULT_LIMIT = 20;
    private static final String VENDOR_BILL_CATEGORY = VendorBillPostingService.POSTING_CATEGORY;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final Clock clock;
    private final ExtSupplierVendorRepository vendors;
    private final ApVendorSettingsRepository settings;
    private final VendorBillRepository bills;
    private final AccountingAuditLogRepository auditLogs;
    private final PostingCategoryRepository postingCategories;
    private final MappingKeyRepository mappingKeys;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<VendorResponse> searchVendors(@Nullable String name, @Nullable String status, int limit) {
        int capped = limit > 0 ? Math.min(limit, MAX_RESULTS) : DEFAULT_LIMIT;
        // LIKE wildcards in the term are literal (escaped with '\\' in the query).
        String term = name == null || name.isBlank()
                ? null
                : name.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String wanted =
                status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (wanted != null && !STATUSES.contains(wanted)) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "status must be ACTIVE or INACTIVE",
                    List.of(new VendorBillException.FieldError("status", "must be ACTIVE or INACTIVE")),
                    null);
        }
        List<ExtSupplierVendor> found = vendors.search(term, wanted, PageRequest.of(0, capped));
        Set<UUID> changed = paymentDetailsChanged(found);
        return found.stream()
                .map(v -> toResponse(v, changed.contains(v.getVendorId()), null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull VendorResponse getVendorById(@NonNull UUID vendorId) {
        ExtSupplierVendor vendor =
                vendors.findById(vendorId).orElseThrow(() -> SupplierVendorCopies.replicationPending(vendorId));
        return read(vendor);
    }

    @Override
    @Transactional
    public @NonNull VendorResponse confirmRemitTo(
            @NonNull UUID vendorId, @NonNull VendorRemitToConfirmationRequest request) {
        String actor = VendorBillDecisions.actor();
        if (request.remitToVersion() == null) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "remitToVersion is required: the vendor's current remit-to version",
                    List.of(new VendorBillException.FieldError("remitToVersion", "is required")),
                    null);
        }
        String justification = VendorBillDecisions.required(request.justification(), "justification");
        ExtSupplierVendor vendor =
                vendors.lockByVendorId(vendorId).orElseThrow(() -> SupplierVendorCopies.replicationPending(vendorId));
        // Separation of duties (Accounting ruling on PR #2648): whoever requested the remit-to in pos-supplier may not
        // also confirm it. Compared as rule 9 compares createdBy: the principal name the approval fields record.
        if (vendor.getRemitToRequestedBy() != null && actor.equals(vendor.getRemitToRequestedBy())) {
            throw new VendorBillException(
                    VendorBillException.Code.VENDOR_REMIT_TO_SELF_CONFIRMATION,
                    "You requested vendor " + vendor.getVendorNumber() + "'s current remit-to; another approver"
                            + " confirms it",
                    List.of(),
                    "Ask another holder of accounting:ap:approve to confirm the remit-to");
        }
        if (request.remitToVersion() != vendor.getRemitToVersion()) {
            throw new VendorBillException(
                    VendorBillException.Code.VENDOR_PAYMENT_DETAILS_CHANGED,
                    "Vendor " + vendor.getVendorNumber() + "'s remit-to is at version " + vendor.getRemitToVersion()
                            + ", not " + request.remitToVersion() + "; review the current one and confirm it",
                    List.of(new VendorBillException.FieldError(
                            "remitToVersion", "is not the vendor's current version " + vendor.getRemitToVersion())),
                    "Read the vendor again (GET /v1/accounting/vendors/{vendorId}) and confirm its current version");
        }
        ApVendorSettings row = settingsFor(vendorId);
        Integer before = row.getConfirmedRemitToVersion();
        Instant now = Instant.now(clock);
        row.setConfirmedRemitToVersion(vendor.getRemitToVersion());
        row.setRemitToConfirmedBy(actor);
        row.setRemitToConfirmedAt(now);
        row.setRemitToConfirmationJustification(justification);
        settings.save(row);
        auditLogs.save(audit(
                vendorId,
                AUDIT_REMIT_TO_CONFIRM,
                actor,
                justification,
                before == null ? null : "confirmedRemitToVersion=" + before,
                "confirmedRemitToVersion=" + vendor.getRemitToVersion() + ";vendorNumber=" + vendor.getVendorNumber()));
        log.info(
                "Vendor {} remit-to version {} confirmed by {}",
                vendor.getVendorNumber(),
                vendor.getRemitToVersion(),
                actor);
        return read(vendor);
    }

    @Override
    @Transactional
    public @NonNull VendorResponse setApSettings(@NonNull UUID vendorId, @NonNull VendorApSettingsRequest request) {
        String actor = VendorBillDecisions.actor();
        String justification = VendorBillDecisions.required(request.getJustification(), "justification");
        List<VendorBillException.FieldError> errors = new ArrayList<>();
        if (request.getRequestId() == null) {
            errors.add(
                    new VendorBillException.FieldError("requestId", "is required: a UUID generated once per change"));
        }
        VendorBillDebitClass debitClass = debitClass(request, errors);
        String key = expenseKey(request, errors);
        refuse(errors);
        ExtSupplierVendor vendor =
                vendors.lockByVendorId(vendorId).orElseThrow(() -> SupplierVendorCopies.replicationPending(vendorId));
        UUID requestId = Objects.requireNonNull(request.getRequestId());
        String fingerprint = fingerprint(vendorId, request, debitClass, key);
        java.util.Optional<AccountingAuditLog> recorded =
                auditLogs.findFirstByOperationAndEntityId(AUDIT_SETTINGS_REQUEST, requestId);
        if (recorded.isPresent()) {
            if (!fingerprint.equals(fingerprintOf(recorded.get().getNewValue()))) {
                throw new IdempotencyConflictException("requestId " + requestId + " was already used for another vendor"
                        + " AP settings change; generate a new requestId for a new change");
            }
            log.info("Vendor AP settings PUT {} replayed; nothing written", requestId);
            return read(vendor);
        }
        ApVendorSettings row = settingsFor(vendorId);
        VendorBillDebitClass newClass = request.hasDefaultDebitClass() ? debitClass : row.getDefaultDebitClass();
        String newKey = request.hasDefaultExpenseMappingKey() ? key : row.getDefaultExpenseMappingKey();
        if (newClass == VendorBillDebitClass.EXPENSE && newKey == null) {
            refuse(List.of(new VendorBillException.FieldError(
                    "defaultExpenseMappingKey", "is required with defaultDebitClass EXPENSE")));
        }
        int changed = 0;
        if (!Objects.equals(newClass, row.getDefaultDebitClass())) {
            auditLogs.save(audit(
                    vendorId,
                    AUDIT_SETTINGS_SET,
                    actor,
                    justification,
                    "defaultDebitClass=" + nullable(row.getDefaultDebitClass()),
                    "defaultDebitClass=" + nullable(newClass) + ";requestId=" + requestId));
            row.setDefaultDebitClass(newClass);
            changed++;
        }
        if (!Objects.equals(newKey, row.getDefaultExpenseMappingKey())) {
            auditLogs.save(audit(
                    vendorId,
                    AUDIT_SETTINGS_SET,
                    actor,
                    justification,
                    "defaultExpenseMappingKey=" + nullable(row.getDefaultExpenseMappingKey()),
                    "defaultExpenseMappingKey=" + nullable(newKey) + ";requestId=" + requestId));
            row.setDefaultExpenseMappingKey(newKey);
            changed++;
        }
        if (changed > 0) {
            settings.save(row);
        }
        auditLogs.save(audit(
                requestId, AUDIT_SETTINGS_REQUEST, actor, justification, null, fingerprint + ";changed=" + changed));
        log.info(
                "Vendor {} AP settings set by {}: {} change(s) (requestId {})",
                vendor.getVendorNumber(),
                actor,
                changed,
                requestId);
        return read(vendor);
    }

    // ---- validation -----------------------------------------------------------------------------------------

    private static @Nullable VendorBillDebitClass debitClass(
            VendorApSettingsRequest request, List<VendorBillException.FieldError> errors) {
        String given = request.getDefaultDebitClass();
        if (!request.hasDefaultDebitClass() || given == null) {
            return null;
        }
        String value = given.trim().toUpperCase(Locale.ROOT);
        if (VendorBillDebitClass.GOODS.name().equals(value)) {
            return VendorBillDebitClass.GOODS;
        }
        if (VendorBillDebitClass.EXPENSE.name().equals(value)) {
            return VendorBillDebitClass.EXPENSE;
        }
        errors.add(new VendorBillException.FieldError("defaultDebitClass", "must be GOODS, EXPENSE or null"));
        return null;
    }

    /** The key given, upper-cased: an active {@code VENDOR_BILL} key {@code EXPENSE_<CODE>}, else a field error. */
    private @Nullable String expenseKey(VendorApSettingsRequest request, List<VendorBillException.FieldError> errors) {
        String given = request.getDefaultExpenseMappingKey();
        if (!request.hasDefaultExpenseMappingKey() || given == null) {
            return null;
        }
        String key = given.trim().toUpperCase(Locale.ROOT);
        boolean active = key.startsWith(VendorBillPostingService.EXPENSE_KEY_PREFIX)
                && key.length() > VendorBillPostingService.EXPENSE_KEY_PREFIX.length()
                && postingCategories
                        .findByCategoryName(VENDOR_BILL_CATEGORY)
                        .flatMap(category -> mappingKeys.findByPostingCategory_PostingCategoryIdAndKeyName(
                                category.getPostingCategoryId(), key))
                        .map(MappingKey::getIsActive)
                        .orElse(false);
        if (!active) {
            errors.add(new VendorBillException.FieldError(
                    "defaultExpenseMappingKey", "must name an active VENDOR_BILL expense key, EXPENSE_<CODE>"));
            return null;
        }
        return key;
    }

    /**
     * What a settings PUT asked for, normalised: the vendor and each field as absent ({@code ~}), null ({@code -}) or
     * its value. Recorded on the request row so a reused requestId with another body is 409, not a silent replay.
     */
    private static String fingerprint(
            UUID vendorId,
            VendorApSettingsRequest request,
            @Nullable VendorBillDebitClass debitClass,
            @Nullable String key) {
        return "vendorId=" + vendorId + ";defaultDebitClass="
                + (!request.hasDefaultDebitClass() ? "~" : debitClass == null ? "-" : debitClass.name())
                + ";defaultExpenseMappingKey="
                + (!request.hasDefaultExpenseMappingKey() ? "~" : key == null ? "-" : key);
    }

    /** The fingerprint part of a request row's new value (everything before {@code ;changed=}). */
    private static String fingerprintOf(@Nullable String newValue) {
        if (newValue == null) {
            return "";
        }
        int changed = newValue.indexOf(";changed=");
        return changed < 0 ? newValue : newValue.substring(0, changed);
    }

    private static void refuse(List<VendorBillException.FieldError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        throw new VendorBillException(
                VendorBillException.Code.VALIDATION_ERROR,
                "The vendor AP settings request is not valid: "
                        + String.join(
                                ", ",
                                errors.stream()
                                        .map(VendorBillException.FieldError::field)
                                        .toList()),
                errors,
                null);
    }

    // ---- reads ----------------------------------------------------------------------------------------------

    private VendorResponse read(ExtSupplierVendor vendor) {
        boolean changed = paymentDetailsChanged(List.of(vendor)).contains(vendor.getVendorId());
        VendorApSettingsResponse apSettings = settings.findByVendorId(vendor.getVendorId())
                .map(row -> new VendorApSettingsResponse(
                        row.getDefaultDebitClass(),
                        row.getDefaultExpenseMappingKey(),
                        row.getConfirmedRemitToVersion(),
                        row.getRemitToConfirmedBy(),
                        row.getRemitToConfirmedAt()))
                .orElse(VendorApSettingsResponse.NONE);
        return toResponse(vendor, changed, apSettings);
    }

    /**
     * The vendors among {@code found} with an approved, open bill approved at another remit-to version than the
     * current one, and no confirmation of the current version (rule 8's flag; the payer is unknown at read time).
     */
    private Set<UUID> paymentDetailsChanged(List<ExtSupplierVendor> found) {
        if (found.isEmpty()) {
            return Set.of();
        }
        Map<UUID, ExtSupplierVendor> byId =
                found.stream().collect(Collectors.toMap(ExtSupplierVendor::getVendorId, Function.identity()));
        Set<UUID> changed = new HashSet<>(
                bills.findVendorIdsWithBillsApprovedAtAnotherRemitTo(byId.keySet(), VendorBillStatus.APPROVED));
        if (changed.isEmpty()) {
            return changed;
        }
        for (ApVendorSettings row : settings.findByVendorIdIn(changed)) {
            ExtSupplierVendor vendor = byId.get(row.getVendorId());
            if (vendor != null && Objects.equals(row.getConfirmedRemitToVersion(), vendor.getRemitToVersion())) {
                changed.remove(row.getVendorId());
            }
        }
        return changed;
    }

    private static VendorResponse toResponse(
            ExtSupplierVendor vendor, boolean paymentDetailsChanged, @Nullable VendorApSettingsResponse apSettings) {
        return VendorResponse.builder()
                .vendorId(vendor.getVendorId())
                .name(vendor.getDisplayName())
                .vendorNumber(vendor.getVendorNumber())
                .status(vendor.getStatus())
                .remitToVersion(vendor.getRemitToVersion())
                .paymentDetailsChanged(paymentDetailsChanged)
                .apSettings(apSettings)
                .build();
    }

    // ---- writes ---------------------------------------------------------------------------------------------

    private ApVendorSettings settingsFor(UUID vendorId) {
        return settings.findByVendorId(vendorId).orElseGet(() -> {
            ApVendorSettings row = new ApVendorSettings();
            row.setVendorId(vendorId);
            return row;
        });
    }

    private AccountingAuditLog audit(
            UUID entityId,
            String operation,
            String actor,
            @Nullable String justification,
            @Nullable String oldValue,
            String newValue) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(AUDIT_ENTITY_TYPE);
        row.setEntityId(entityId);
        row.setOperation(operation);
        row.setUserId(actor);
        row.setTimestamp(Instant.now(clock));
        row.setJustification(justification);
        row.setOldValue(oldValue);
        row.setNewValue(newValue);
        return row;
    }

    private static String nullable(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }
}
