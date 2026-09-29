package com.positivity.accounting.internal.bankfeed.file.parser;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Which column of a statement file holds which value (SPEC-manual-bank-reconciliation §3.3 {@code
 * columnMapping}; story S3, #2302). Each field names a column by its header text (matched ignoring
 * case and surrounding blanks) or by its zero-based position; an absent field is not mapped.
 *
 * <p>The JSON form is the {@code bank_import.column_mapping} / {@code
 * bank_account_profile.default_column_mapping} object: {@code {"date": "Posted Date", "description":
 * 1, "amount": "Amount", "reference": "Ref"}}.
 */
public record ColumnMapping(
        @Nullable ColumnRef date,
        @Nullable ColumnRef description,
        @Nullable ColumnRef amount,
        @Nullable ColumnRef debit,
        @Nullable ColumnRef credit,
        @Nullable ColumnRef reference,
        @Nullable ColumnRef checkNumber,
        @Nullable ColumnRef sourceTransactionId) {

    /** The mapping keys, in the order the JSON form writes them. */
    public static final List<String> KEYS = List.of(
            "date", "description", "amount", "debit", "credit", "reference", "checkNumber", "sourceTransactionId");

    /** A column named by header text or by zero-based position; exactly one is set. */
    public record ColumnRef(@Nullable String name, @Nullable Integer index) {

        public static @NonNull ColumnRef named(@NonNull String name) {
            return new ColumnRef(name, null);
        }

        public static @NonNull ColumnRef at(int index) {
            return new ColumnRef(null, index);
        }

        /** The JSON value: the header text or the position. */
        public @NonNull Object toJson() {
            return name != null ? name : index;
        }
    }

    /** The F2 defaults by header name: {@code date, description, amount, reference}. */
    public static @NonNull ColumnMapping defaultsByName() {
        return new ColumnMapping(
                ColumnRef.named("date"),
                ColumnRef.named("description"),
                ColumnRef.named("amount"),
                null,
                null,
                ColumnRef.named("reference"),
                null,
                null);
    }

    /** The F2 defaults by position for a file without a header row: columns 0, 1, 2 and 3. */
    public static @NonNull ColumnMapping defaultsByPosition() {
        return new ColumnMapping(
                ColumnRef.at(0), ColumnRef.at(1), ColumnRef.at(2), null, null, ColumnRef.at(3), null, null);
    }

    /**
     * Reads the JSON form.
     *
     * @param field the request field the map came from, for {@code fieldErrors}
     * @throws BankRecException {@code VALIDATION_ERROR} naming each unknown key or bad value
     */
    public static @NonNull ColumnMapping fromJson(@NonNull Map<String, Object> json, @NonNull String field) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            String key = entry.getKey();
            if (!KEYS.contains(key)) {
                errors.put(field + "." + key, "unknown column; expected one of " + KEYS);
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            ColumnRef ref = toRef(value);
            if (ref == null) {
                errors.put(field + "." + key, "a header name or a zero-based column index");
            } else {
                refs.put(key, ref);
            }
        }
        if (!errors.isEmpty()) {
            throw new BankRecException(BankRecErrorCode.VALIDATION_ERROR, "The column mapping is invalid", errors);
        }
        return new ColumnMapping(
                refs.get("date"),
                refs.get("description"),
                refs.get("amount"),
                refs.get("debit"),
                refs.get("credit"),
                refs.get("reference"),
                refs.get("checkNumber"),
                refs.get("sourceTransactionId"));
    }

    private static @Nullable ColumnRef toRef(Object value) {
        if (value instanceof String name) {
            return name.isBlank() || name.length() > 255 ? null : ColumnRef.named(name.trim());
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            int asInt = number.intValue();
            return asDouble == asInt && asInt >= 0 ? ColumnRef.at(asInt) : null;
        }
        return null;
    }

    /** The JSON form, with only the mapped keys. */
    public @NonNull Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        put(json, "date", date);
        put(json, "description", description);
        put(json, "amount", amount);
        put(json, "debit", debit);
        put(json, "credit", credit);
        put(json, "reference", reference);
        put(json, "checkNumber", checkNumber);
        put(json, "sourceTransactionId", sourceTransactionId);
        return json;
    }

    private static void put(Map<String, Object> json, String key, @Nullable ColumnRef ref) {
        if (ref != null) {
            json.put(key, ref.toJson());
        }
    }

    /** Whether every column is named by header text (such a mapping needs a header row). */
    boolean usesNames() {
        return toJson().values().stream().anyMatch(String.class::isInstance);
    }
}
