package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.dto.VendorApHoldRequest;
import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorApSettingsResponse;
import com.positivity.accounting.internal.dto.VendorInformationReturnRequest;
import com.positivity.accounting.internal.dto.VendorRemitToConfirmationRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
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
 *
 * <p><b>AP hold and information return (#2615).</b> A hold set or its reason changed writes {@value #AUDIT_HOLD_SET}
 * (old to new hold and reason); a release writes {@value #AUDIT_HOLD_CLEARED} (the old reason); each with the actor,
 * the justification and the {@code requestId}. Each changed information-return field writes an {@value
 * #AUDIT_SETTINGS_SET} row. The hold reason is CONFIDENTIAL (ADR-0072): it reaches the audit rows and the reads, never
 * a log line, an exception message or the request fingerprint, which carries its SHA-256 instead. pos-tax is called
 * only when the information return changes to a reportable value; a refusal of either writes nothing. That call is
 * made under the vendor copy's row lock, because whether anything changed is known only from the locked settings
 * row; the client's connect and read timeouts (2 s, 5 s) bound how long the lock is held for it.
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
    static final String AUDIT_HOLD_SET = "AP_VENDOR_HOLD_SET";
    static final String AUDIT_HOLD_CLEARED = "AP_VENDOR_HOLD_CLEARED";

    /** The hold reason's bounds once trimmed (ruling 3 of #2615). */
    static final int MIN_HOLD_REASON = 10;

    static final int MAX_HOLD_REASON = 500;

    /** Code shapes checked before pos-tax is asked (the same shapes pos-tax's startup check enforces). */
    private static final Pattern FORM_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    private static final Pattern BOX_CODE = Pattern.compile("^[A-Z0-9]{1,10}$");
    private static final Pattern SCHEME_CODE = Pattern.compile("^[A-Z][A-Z_]{1,15}$");

    private static final int DEFAULT_LIMIT = 20;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final Clock clock;
    private final ExtSupplierVendorRepository vendors;
    private final ApVendorSettingsRepository settings;
    private final VendorBillRepository bills;
    private final AccountingAuditLogRepository auditLogs;
    private final VendorBillExpenseKeys expenseKeys;
    private final ActorDisplayNames actorNames;
    private final InformationReturnFormsService informationReturnForms;

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
        // One settings query for the page (#2615): the apHold flags and the remit-to confirmations.
        Map<UUID, ApVendorSettings> rows = found.isEmpty()
                ? Map.of()
                : settings
                        .findByVendorIdIn(found.stream()
                                .map(ExtSupplierVendor::getVendorId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(ApVendorSettings::getVendorId, Function.identity(), (a, b) -> a));
        Set<UUID> changed = paymentDetailsChanged(found, rows);
        return found.stream()
                .map(v -> toResponse(
                        v,
                        changed.contains(v.getVendorId()),
                        rows.containsKey(v.getVendorId())
                                && rows.get(v.getVendorId()).isApHold(),
                        null))
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
        VendorDirectoryService.refuseUnknown(request.unknownProperties());
        String actor = VendorBillDecisions.actor();
        String justification = VendorBillDecisions.required(request.getJustification(), "justification");
        List<VendorBillException.FieldError> errors = new ArrayList<>();
        if (request.getRequestId() == null) {
            errors.add(
                    new VendorBillException.FieldError("requestId", "is required: a UUID generated once per change"));
        }
        VendorBillDebitClass debitClass = debitClass(request, errors);
        String key = expenseKey(request, errors);
        HoldChange hold = holdChange(request, errors);
        InformationReturnChange informationReturn = informationReturnChange(request, errors);
        if (request.hasAcceptTaxOnResaleGoods() && request.getAcceptTaxOnResaleGoods() == null) {
            // CAP:550 S43: a boolean; the column is NOT NULL, so null has no meaning to store.
            errors.add(new VendorBillException.FieldError("acceptTaxOnResaleGoods", "must be true or false"));
        }
        refuse(errors);
        if (hold != null && hold.onHold() && hold.reason() == null) {
            // Never echoes the reason: only its field and the bounds.
            throw new VendorBillException(
                    VendorBillException.Code.JUSTIFICATION_REQUIRED,
                    "apHold.reason of at least " + MIN_HOLD_REASON + " characters is required to hold a vendor",
                    List.of(new VendorBillException.FieldError(
                            "apHold.reason", "at least " + MIN_HOLD_REASON + " characters are required")),
                    null);
        }
        ExtSupplierVendor vendor =
                vendors.lockByVendorId(vendorId).orElseThrow(() -> SupplierVendorCopies.replicationPending(vendorId));
        UUID requestId = Objects.requireNonNull(request.getRequestId());
        String fingerprint = fingerprint(vendorId, request, debitClass, key, hold, informationReturn);
        Optional<AccountingAuditLog> recorded =
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
        // pos-tax is asked only now, after the replay check and before anything is written, and only when the
        // information return changes to a reportable value (a 503 or a refusal writes nothing).
        if (informationReturn != null && informationReturn.reportable() && informationReturn.differsFrom(row)) {
            requireConfigured(informationReturn, informationReturnForms.forms());
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
        if (hold != null) {
            changed += applyHold(row, hold, vendorId, actor, justification, requestId);
        }
        if (informationReturn != null) {
            changed += applyInformationReturn(row, informationReturn, vendorId, actor, justification, requestId);
        }
        Boolean acceptTaxOnResaleGoods = request.getAcceptTaxOnResaleGoods();
        if (request.hasAcceptTaxOnResaleGoods()
                && acceptTaxOnResaleGoods != null
                && acceptTaxOnResaleGoods != row.isAcceptTaxOnResaleGoods()) {
            // CAP:550 S43: honoured at the next decision; a bill already approved is untouched.
            auditSetting(
                    vendorId,
                    actor,
                    justification,
                    requestId,
                    "acceptTaxOnResaleGoods",
                    row.isAcceptTaxOnResaleGoods(),
                    acceptTaxOnResaleGoods);
            row.setAcceptTaxOnResaleGoods(acceptTaxOnResaleGoods);
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
        // The expense-category read lists exactly the keys this accepts (VendorBillExpenseKeys, #2670).
        if (!expenseKeys.isActive(key)) {
            errors.add(new VendorBillException.FieldError(
                    "defaultExpenseMappingKey", "must name an active VENDOR_BILL expense key, EXPENSE_<CODE>"));
            return null;
        }
        return key;
    }

    /**
     * The hold a PUT asks for (#2615), or null when {@code apHold} is absent.
     *
     * @param onHold whether to hold
     * @param reason the trimmed reason, null when absent, blank or under {@value #MIN_HOLD_REASON} characters
     * @param reasonGiven the fingerprint's view of the reason: absent, null, or present
     * @param reasonHash the SHA-256 hex of the trimmed reason as sent, null unless a string was sent
     */
    record HoldChange(
            boolean onHold,
            @Nullable String reason,
            boolean reasonGiven,
            @Nullable String reasonHash) {

        @Override
        public String toString() {
            // The reason is CONFIDENTIAL (ADR-0072): never printed.
            return "HoldChange[onHold=" + onHold + "]";
        }
    }

    /**
     * The information return a PUT asks for (#2615), or null when {@code informationReturn} is absent; codes trimmed and
     * upper-cased. Each {@code *Given} records the key's presence for the fingerprint.
     */
    record InformationReturnChange(
            boolean reportable,
            @Nullable String form,
            @Nullable String box,
            @Nullable String scheme,
            boolean formGiven,
            boolean boxGiven,
            boolean schemeGiven) {

        /** Whether storing this would change {@code row}'s flag, form, box or scheme. */
        boolean differsFrom(ApVendorSettings row) {
            return reportable != row.isInformationReturnReportable()
                    || !Objects.equals(form, row.getInformationReturnForm())
                    || !Objects.equals(box, row.getInformationReturnBox())
                    || !Objects.equals(scheme, row.getInformationReturnPayeeScheme());
        }
    }

    /** The {@code apHold} object's shape (#2615); a reason too short is refused afterwards as JUSTIFICATION_REQUIRED. */
    private static @Nullable HoldChange holdChange(
            VendorApSettingsRequest request, List<VendorBillException.FieldError> errors) {
        if (!request.hasApHold()) {
            return null;
        }
        VendorApHoldRequest hold = request.getApHold();
        if (hold == null) {
            errors.add(new VendorBillException.FieldError(
                    "apHold", "must be an object; send onHold false to release a hold"));
            return null;
        }
        if (hold.getOnHold() == null) {
            errors.add(new VendorBillException.FieldError("apHold.onHold", "is required: true or false"));
            return null;
        }
        // Counted as V22's CHECK and varchar(500) count it: code points of the stripped text (char_length(btrim(...)));
        // strip() removes what btrim's spaces would, so the stored reason has the same length here and there. Text
        // made only of whitespace or space separators (no-break spaces included) is no reason at all.
        String trimmed = hold.getReason() == null ? null : hold.getReason().strip();
        if (trimmed != null
                && (trimmed.isBlank() || trimmed.codePoints().allMatch(VendorDirectoryServiceImpl::space))) {
            trimmed = "";
        }
        int length = trimmed == null ? 0 : trimmed.codePointCount(0, trimmed.length());
        if (hold.getOnHold() && length > MAX_HOLD_REASON) {
            errors.add(new VendorBillException.FieldError(
                    "apHold.reason", "must be at most " + MAX_HOLD_REASON + " characters"));
            return null;
        }
        String reason = length >= MIN_HOLD_REASON ? trimmed : null;
        return new HoldChange(hold.getOnHold(), reason, hold.hasReason(), trimmed == null ? null : sha256(trimmed));
    }

    /** Whether a code point is whitespace or a space separator (U+00A0 and U+202F included). */
    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** The {@code informationReturn} object's shape (#2615), before pos-tax is asked. */
    private static @Nullable InformationReturnChange informationReturnChange(
            VendorApSettingsRequest request, List<VendorBillException.FieldError> errors) {
        if (!request.hasInformationReturn()) {
            return null;
        }
        VendorInformationReturnRequest given = request.getInformationReturn();
        if (given == null) {
            errors.add(new VendorBillException.FieldError(
                    "informationReturn", "must be an object; send reportable false to clear the flag"));
            return null;
        }
        if (given.getReportable() == null) {
            errors.add(
                    new VendorBillException.FieldError("informationReturn.reportable", "is required: true or false"));
            return null;
        }
        String form = code(given.getForm());
        String box = code(given.getBox());
        String scheme = code(given.getPayeeTaxRegistrationScheme());
        int before = errors.size();
        if (given.getReportable()) {
            if (form == null) {
                errors.add(new VendorBillException.FieldError("informationReturn.form", "is required when reportable"));
            } else if (!FORM_CODE.matcher(form).matches()) {
                errors.add(new VendorBillException.FieldError(
                        "informationReturn.form", "is not a form code of the tax country's information returns"));
            }
            if (box == null) {
                errors.add(new VendorBillException.FieldError("informationReturn.box", "is required when reportable"));
            } else if (!BOX_CODE.matcher(box).matches()) {
                errors.add(new VendorBillException.FieldError("informationReturn.box", "is not a box of the form"));
            }
            if (scheme != null && !SCHEME_CODE.matcher(scheme).matches()) {
                errors.add(new VendorBillException.FieldError(
                        "informationReturn.payeeTaxRegistrationScheme", "is not a payee-id scheme of the form"));
            }
        } else {
            notWhenUnreportable(form, "informationReturn.form", errors);
            notWhenUnreportable(box, "informationReturn.box", errors);
            notWhenUnreportable(scheme, "informationReturn.payeeTaxRegistrationScheme", errors);
        }
        if (errors.size() > before) {
            return null;
        }
        return new InformationReturnChange(
                given.getReportable(),
                given.getReportable() ? form : null,
                given.getReportable() ? box : null,
                given.getReportable() ? scheme : null,
                given.hasForm(),
                given.hasBox(),
                given.hasPayeeTaxRegistrationScheme());
    }

    private static void notWhenUnreportable(
            @Nullable String value, String field, List<VendorBillException.FieldError> errors) {
        if (value != null) {
            errors.add(new VendorBillException.FieldError(field, "must be absent or null when not reportable"));
        }
    }

    /** A code as sent, trimmed and upper-cased; null when absent or blank. */
    private static @Nullable String code(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The information return against pos-tax's configured forms for the tax country (#2615): the form is one of them,
     * the box one of its boxes, the scheme (when given) one of its payee-id schemes. A country with no form refuses
     * any reportable vendor. Each violation is a 400 {@code VALIDATION_ERROR} field error; nothing is written.
     */
    private static void requireConfigured(InformationReturnChange change, InformationReturnFormsResponse configured) {
        List<VendorBillException.FieldError> errors = new ArrayList<>();
        if (configured.forms().isEmpty()) {
            errors.add(new VendorBillException.FieldError(
                    "informationReturn.reportable",
                    "the tax country " + configured.countryCode() + " configures no information-return form"));
            refuse(errors);
        }
        InformationReturnFormsResponse.Form form = configured.form(Objects.requireNonNull(change.form()));
        if (form == null) {
            errors.add(new VendorBillException.FieldError(
                    "informationReturn.form",
                    "is not a form configured for the tax country " + configured.countryCode()));
        } else {
            if (!form.hasBox(Objects.requireNonNull(change.box()))) {
                errors.add(new VendorBillException.FieldError("informationReturn.box", "is not a box of the form"));
            }
            if (change.scheme() != null && !form.payeeIdSchemes().contains(change.scheme())) {
                errors.add(new VendorBillException.FieldError(
                        "informationReturn.payeeTaxRegistrationScheme", "is not a payee-id scheme of the form"));
            }
        }
        refuse(errors);
    }

    /**
     * What a settings PUT asked for, normalised: the vendor and each field as absent ({@code ~}), null ({@code -}) or
     * its value. Recorded on the request row so a reused requestId with another body is 409, not a silent replay. The
     * hold reason enters as its SHA-256 hex, so free text never reaches the {@code ;}-separated form or the row. S43's
     * {@code acceptTaxOnResaleGoods} comes last.
     */
    private static String fingerprint(
            UUID vendorId,
            VendorApSettingsRequest request,
            @Nullable VendorBillDebitClass debitClass,
            @Nullable String key,
            @Nullable HoldChange hold,
            @Nullable InformationReturnChange informationReturn) {
        StringBuilder fingerprint = new StringBuilder("vendorId=")
                .append(vendorId)
                .append(";defaultDebitClass=")
                .append(!request.hasDefaultDebitClass() ? "~" : debitClass == null ? "-" : debitClass.name())
                .append(";defaultExpenseMappingKey=")
                .append(!request.hasDefaultExpenseMappingKey() ? "~" : key == null ? "-" : key);
        if (hold == null) {
            fingerprint.append(";apHold=~");
        } else {
            fingerprint
                    .append(";apHold.onHold=")
                    .append(hold.onHold())
                    .append(";apHold.reason=")
                    .append(given(hold.reasonGiven(), hold.reasonHash()));
        }
        if (informationReturn == null) {
            fingerprint.append(";informationReturn=~");
        } else {
            fingerprint
                    .append(";informationReturn.reportable=")
                    .append(informationReturn.reportable())
                    .append(";informationReturn.form=")
                    .append(given(informationReturn.formGiven(), informationReturn.form()))
                    .append(";informationReturn.box=")
                    .append(given(informationReturn.boxGiven(), informationReturn.box()))
                    .append(";informationReturn.payeeTaxRegistrationScheme=")
                    .append(given(informationReturn.schemeGiven(), informationReturn.scheme()));
        }
        // CAP:550 S43: absent ~, else its value (null is refused before the fingerprint is taken).
        fingerprint
                .append(";acceptTaxOnResaleGoods=")
                .append(
                        request.hasAcceptTaxOnResaleGoods()
                                ? String.valueOf(request.getAcceptTaxOnResaleGoods())
                                : "~");
        return fingerprint.toString();
    }

    private static String given(boolean present, @Nullable String value) {
        return !present ? "~" : value == null ? "-" : value;
    }

    /** The SHA-256 hex of {@code text} (UTF-8). */
    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
        Optional<ApVendorSettings> row = settings.findByVendorId(vendor.getVendorId());
        boolean changed = paymentDetailsChanged(
                        List.of(vendor),
                        row.map(r -> Map.of(vendor.getVendorId(), r)).orElse(Map.of()))
                .contains(vendor.getVendorId());
        // The two actors' names in one query (#2670); null when not known, never the username.
        Map<String, String> names = row.map(r -> actorNames.namesOf(
                        Arrays.asList(r.getRemitToConfirmedBy(), r.isApHold() ? r.getApHoldSetBy() : null)))
                .orElse(Map.of());
        VendorApSettingsResponse apSettings = row.map(r -> new VendorApSettingsResponse(
                        r.getDefaultDebitClass(),
                        r.getDefaultExpenseMappingKey(),
                        r.getConfirmedRemitToVersion(),
                        r.getRemitToConfirmedBy(),
                        ActorDisplayNames.nameOf(names, r.getRemitToConfirmedBy()),
                        r.getRemitToConfirmedAt(),
                        r.isApHold()
                                ? new VendorApSettingsResponse.ApHold(
                                        true,
                                        r.getApHoldReason(),
                                        r.getApHoldSetBy(),
                                        ActorDisplayNames.nameOf(names, r.getApHoldSetBy()),
                                        r.getApHoldSetAt())
                                : VendorApSettingsResponse.ApHold.NONE,
                        informationReturn(r, vendor),
                        r.isAcceptTaxOnResaleGoods()))
                .orElse(VendorApSettingsResponse.NONE);
        return toResponse(vendor, changed, apSettings.apHold().onHold(), apSettings);
    }

    /**
     * The information-return flag as the vendor read serves it (#2615): {@code payeeTinOnFile} when the copy holds a
     * registration of the chosen scheme, and {@code payeeTinLast4} relayed unchanged from the one such registration
     * (null with none, several, or a null {@code last4}). Never derived here, never logged (ADR-0072).
     */
    static VendorApSettingsResponse.InformationReturn informationReturn(
            ApVendorSettings row, ExtSupplierVendor vendor) {
        if (!row.isInformationReturnReportable()) {
            return VendorApSettingsResponse.InformationReturn.NONE;
        }
        String scheme = row.getInformationReturnPayeeScheme();
        List<Map<String, String>> ofScheme = scheme == null || vendor.getTaxRegistrations() == null
                ? List.of()
                : vendor.getTaxRegistrations().stream()
                        .filter(registration -> registration != null && scheme.equals(registration.get("scheme")))
                        .toList();
        return new VendorApSettingsResponse.InformationReturn(
                true,
                row.getInformationReturnForm(),
                row.getInformationReturnBox(),
                scheme,
                !ofScheme.isEmpty(),
                ofScheme.size() == 1 ? ofScheme.get(0).get("last4") : null);
    }

    /**
     * The vendors among {@code found} with an approved, open bill approved at another remit-to version than the
     * current one, and no confirmation of the current version (rule 8's flag; the payer is unknown at read time).
     *
     * @param rows the found vendors' settings rows, already read (one query per page)
     */
    private Set<UUID> paymentDetailsChanged(List<ExtSupplierVendor> found, Map<UUID, ApVendorSettings> rows) {
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
        changed.removeIf(vendorId -> {
            ApVendorSettings row = rows.get(vendorId);
            ExtSupplierVendor vendor = byId.get(vendorId);
            return row != null
                    && vendor != null
                    && Objects.equals(row.getConfirmedRemitToVersion(), vendor.getRemitToVersion());
        });
        return changed;
    }

    private static VendorResponse toResponse(
            ExtSupplierVendor vendor,
            boolean paymentDetailsChanged,
            boolean apHold,
            @Nullable VendorApSettingsResponse apSettings) {
        return VendorResponse.builder()
                .vendorId(vendor.getVendorId())
                .name(vendor.getDisplayName())
                .vendorNumber(vendor.getVendorNumber())
                .status(vendor.getStatus())
                .remitToVersion(vendor.getRemitToVersion())
                .paymentDetailsChanged(paymentDetailsChanged)
                .apHold(apHold)
                .apSettings(apSettings)
                .build();
    }

    // ---- writes ---------------------------------------------------------------------------------------------

    /**
     * Applies a hold change to {@code row} (#2615): set, a new reason, or a release, each one audit row; the same
     * reason again, or a release of a vendor not held, changes nothing and writes none.
     *
     * @return 1 when the row changed, else 0
     */
    private int applyHold(
            ApVendorSettings row, HoldChange hold, UUID vendorId, String actor, String justification, UUID requestId) {
        if (hold.onHold()) {
            String reason = Objects.requireNonNull(hold.reason());
            if (row.isApHold() && reason.equals(row.getApHoldReason())) {
                return 0;
            }
            auditLogs.save(audit(
                    vendorId,
                    AUDIT_HOLD_SET,
                    actor,
                    justification,
                    row.isApHold() ? "apHold=true;reason=" + row.getApHoldReason() : "apHold=false",
                    "apHold=true;reason=" + reason + ";requestId=" + requestId));
            row.setApHold(true);
            row.setApHoldReason(reason);
            row.setApHoldSetBy(actor);
            row.setApHoldSetAt(Instant.now(clock));
            return 1;
        }
        if (!row.isApHold()) {
            return 0;
        }
        auditLogs.save(audit(
                vendorId,
                AUDIT_HOLD_CLEARED,
                actor,
                justification,
                "apHold=true;reason=" + row.getApHoldReason(),
                "apHold=false;requestId=" + requestId));
        row.setApHold(false);
        row.setApHoldReason(null);
        row.setApHoldSetBy(null);
        row.setApHoldSetAt(null);
        return 1;
    }

    /**
     * Applies an information-return change to {@code row} (#2615): one {@value #AUDIT_SETTINGS_SET} row per field that
     * changed, old to new.
     *
     * @return the number of fields that changed
     */
    private int applyInformationReturn(
            ApVendorSettings row,
            InformationReturnChange change,
            UUID vendorId,
            String actor,
            String justification,
            UUID requestId) {
        int changed = 0;
        if (change.reportable() != row.isInformationReturnReportable()) {
            auditSetting(
                    vendorId,
                    actor,
                    justification,
                    requestId,
                    "informationReturnReportable",
                    row.isInformationReturnReportable(),
                    change.reportable());
            row.setInformationReturnReportable(change.reportable());
            changed++;
        }
        if (!Objects.equals(change.form(), row.getInformationReturnForm())) {
            auditSetting(
                    vendorId,
                    actor,
                    justification,
                    requestId,
                    "informationReturnForm",
                    row.getInformationReturnForm(),
                    change.form());
            row.setInformationReturnForm(change.form());
            changed++;
        }
        if (!Objects.equals(change.box(), row.getInformationReturnBox())) {
            auditSetting(
                    vendorId,
                    actor,
                    justification,
                    requestId,
                    "informationReturnBox",
                    row.getInformationReturnBox(),
                    change.box());
            row.setInformationReturnBox(change.box());
            changed++;
        }
        if (!Objects.equals(change.scheme(), row.getInformationReturnPayeeScheme())) {
            auditSetting(
                    vendorId,
                    actor,
                    justification,
                    requestId,
                    "informationReturnPayeeScheme",
                    row.getInformationReturnPayeeScheme(),
                    change.scheme());
            row.setInformationReturnPayeeScheme(change.scheme());
            changed++;
        }
        return changed;
    }

    private void auditSetting(
            UUID vendorId,
            String actor,
            String justification,
            UUID requestId,
            String setting,
            @Nullable Object before,
            @Nullable Object after) {
        auditLogs.save(audit(
                vendorId,
                AUDIT_SETTINGS_SET,
                actor,
                justification,
                setting + "=" + nullable(before),
                setting + "=" + nullable(after) + ";requestId=" + requestId));
    }

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
