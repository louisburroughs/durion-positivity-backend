package com.positivity.web.common;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Classifies a persistence-layer integrity failure by the SQLSTATE of the underlying
 * {@link SQLException}, so {@link GlobalApiExceptionHandler} can map it to the HTTP status
 * required by ADR-0017 (409 for stateful collisions such as unique/foreign-key violations,
 * 422 for semantic violations such as not-null/check failures on client-supplied values).
 *
 * <p>SQLSTATE class 23 (integrity constraint violation) is standardized, so the codes below
 * cover both PostgreSQL (docker/alpha/prod) and H2 (dev):
 *
 * <ul>
 *   <li>{@code 23505} — unique violation</li>
 *   <li>{@code 23502} — not-null violation</li>
 *   <li>{@code 23514} (PostgreSQL) / {@code 23513} (H2) — check violation</li>
 *   <li>{@code 23503} / {@code 23506} (H2 child-exists) — foreign-key violation</li>
 * </ul>
 *
 * <p>Constraint and column names are extracted best-effort from the driver message. Only
 * identifier names are ever extracted — never rejected data values, which must not be echoed
 * back to clients.
 */
public final class DataIntegrityViolations {

    /** What kind of integrity constraint was violated. */
    public enum Kind {
        UNIQUE,
        NOT_NULL,
        CHECK,
        FOREIGN_KEY,
        /** SQLSTATE class 23 but none of the specific codes above. */
        OTHER_INTEGRITY,
        /** No SQLSTATE available, or not an integrity-constraint SQLSTATE. */
        UNKNOWN
    }

    /**
     * Classification result. {@code constraintName} and {@code columnName} are best-effort
     * extractions from the driver message and may be {@code null}.
     */
    public record Classification(
            @NonNull Kind kind,
            @Nullable String constraintName,
            @Nullable String columnName) {}

    /**
     * Columns populated by the server (JPA auditing), never by the client. A not-null
     * violation on one of these is a server defect (for example a missing
     * {@code AuditorAware}, see issue #1464), not a client error.
     */
    private static final Set<String> SERVER_POPULATED_AUDIT_COLUMNS = Set.of(
            "created_by",
            "updated_by",
            "last_modified_by",
            "created_at",
            "updated_at",
            "created_date",
            "last_modified_at",
            "last_modified_date");

    /** PostgreSQL and H2 both quote the offending column: {@code ... column "created_by" ...}. */
    private static final Pattern COLUMN_PATTERN = Pattern.compile("column \"([^\"]+)\"");

    /** PostgreSQL quotes the violated constraint: {@code ... violates unique constraint "uq_x" ...}. */
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile("constraint \"([^\"]+)\"");

    /** H2 names the index in unique-violation messages: {@code ... violation: "PUBLIC.UQ_X_INDEX_4 ON ..." ...}. */
    private static final Pattern H2_UNIQUE_PATTERN = Pattern.compile("violation: \"(?:[A-Za-z0-9_]+\\.)?([^ \"]+)");

    private DataIntegrityViolations() {
        // Utility class - prevent instantiation
    }

    /** Classifies the given failure by the SQLSTATE of the first {@link SQLException} in its cause chain. */
    public static @NonNull Classification classify(@NonNull Throwable exception) {
        SQLException sqlException = firstSqlException(exception);
        if (sqlException == null) {
            return new Classification(Kind.UNKNOWN, null, null);
        }
        String sqlState = sqlException.getSQLState();
        String message = sqlException.getMessage() == null ? "" : sqlException.getMessage();
        if (sqlState == null) {
            return new Classification(Kind.UNKNOWN, null, null);
        }
        return switch (sqlState) {
            case "23505" -> new Classification(Kind.UNIQUE, extractConstraintName(message), null);
            case "23502" -> new Classification(Kind.NOT_NULL, null, extractColumnName(message));
            case "23513", "23514" -> new Classification(Kind.CHECK, extractConstraintName(message), null);
            case "23503", "23506" -> new Classification(Kind.FOREIGN_KEY, extractConstraintName(message), null);
            default ->
                sqlState.startsWith("23")
                        ? new Classification(Kind.OTHER_INTEGRITY, extractConstraintName(message), null)
                        : new Classification(Kind.UNKNOWN, null, null);
        };
    }

    /** True when the column is populated server-side (JPA auditing) and a not-null failure on it is a server defect. */
    public static boolean isServerPopulatedAuditColumn(@Nullable String columnName) {
        return columnName != null && SERVER_POPULATED_AUDIT_COLUMNS.contains(columnName.toLowerCase(Locale.ROOT));
    }

    private static @Nullable SQLException firstSqlException(Throwable exception) {
        Throwable current = exception;
        // Bounded walk: cause chains can be cyclic in pathological cases.
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static @Nullable String extractConstraintName(String message) {
        Matcher postgres = CONSTRAINT_PATTERN.matcher(message);
        if (postgres.find()) {
            return postgres.group(1);
        }
        Matcher h2 = H2_UNIQUE_PATTERN.matcher(message);
        if (h2.find()) {
            return h2.group(1);
        }
        return null;
    }

    private static @Nullable String extractColumnName(String message) {
        return Optional.of(COLUMN_PATTERN.matcher(message))
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .orElse(null);
    }
}
