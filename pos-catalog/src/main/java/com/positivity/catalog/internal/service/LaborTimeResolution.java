package com.positivity.catalog.internal.service;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Internal result of a labor-time resolution (#1569 Phase 1, sourcing plan §3.4). A miss is a
 * typed status, never an exception — callers degrade the way {@code SupplierStockService}
 * callers do: render without a default and let the writer type the hours.
 *
 * @param status whether a time was found, and if not, why not
 * @param laborHours decimal hours in tenths; present only when RESOLVED
 * @param timeType which time class answered; present only when RESOLVED
 * @param sourceCode provenance source; present only when RESOLVED
 * @param sourceRevision provenance revision; present only when RESOLVED
 * @param matchGrade how confidently the vehicle matched; present only when RESOLVED
 * @param overlapGroup shared-setup group for overlap-aware summation
 * @param includedOpCodes Durion operation codes whose time is included in this one
 */
public record LaborTimeResolution(
        @NonNull Status status,
        @Nullable BigDecimal laborHours,
        @Nullable String timeType,
        @Nullable String sourceCode,
        @Nullable String sourceRevision,
        @Nullable MatchGrade matchGrade,
        @Nullable String overlapGroup,
        @NonNull List<String> includedOpCodes) {

    /** Typed resolution outcomes (sourcing plan §6.1 response contract). */
    public enum Status {
        /** A time was found; the provenance fields say where it came from. */
        RESOLVED,
        /** Nothing stored, nothing live, no default hours. Writer types the hours. */
        NO_TIME_AVAILABLE,
        /** A live-only source failed and nothing else answered; retry may succeed. */
        SOURCE_UNAVAILABLE
    }

    /** Vehicle-match confidence, exact-first then widening (sourcing plan §3.4). */
    public enum MatchGrade {
        /** Every vehicle-key field the row states matched, including submodel and engine. */
        EXACT,
        /** Matched with the row silent on engine and/or submodel. */
        ENGINE_WILDCARD,
        /** Matched on make/model (year wild on the row) — the coarsest stored answer. */
        MODEL_LEVEL,
        /** No vehicle-keyed row; the service's scalar default_labor_hours answered. */
        DEFAULT_HOURS
    }

    public static LaborTimeResolution miss(@NonNull Status status) {
        return new LaborTimeResolution(status, null, null, null, null, null, null, List.of());
    }
}
