package com.positivity.catalog.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Typed answer to a labor-time resolution (#1569 Phase 1, ADR-0058 §5). This is the degradation
 * contract of the scoped synchronous read: a miss or a vendor-side failure never surfaces as an
 * exception, always as a non-{@link Status#RESOLVED} status the caller renders around — the
 * estimate line stays editable and the writer types the hours.
 *
 * @param status whether a time was found, and if not, why not
 * @param laborHours decimal hours in tenths; present only when RESOLVED
 * @param timeType which time class answered; present only when RESOLVED
 * @param sourceCode provenance source (e.g. {@code MOCKGUIDE}, {@code DURION})
 * @param sourceRevision provenance revision — what makes the number defensible on an invoice
 * @param matchGrade vehicle-match confidence; callers may show it beside the prefill
 * @param overlapGroup shared-setup group for overlap-aware summation
 * @param includedOpCodes Durion operation codes whose time this one already includes
 * @param ownerScope {@code SHOP} when the quoting location's own authored time answered,
 *     {@code PLATFORM} otherwise
 */
@Schema(
        name = "LaborTimeQuoteResponse",
        description = "The resolved labor time with provenance, or a typed miss. Callers must degrade on"
                + " non-RESOLVED statuses — render the line without a prefill — never fail their flow.")
public record LaborTimeQuoteResponse(
        @Schema(description = "Whether a time was found, and if not, why not.") @NonNull
        Status status,

        @Schema(description = "Decimal hours in tenths; only when RESOLVED.", example = "1.5") @Nullable
        BigDecimal laborHours,

        @Schema(description = "Time class that answered.", example = "RETAIL_FLAT_RATE") @Nullable
        String timeType,

        @Schema(description = "Provenance source.", example = "MOCKGUIDE") @Nullable
        String sourceCode,

        @Schema(description = "Provenance revision.", example = "2026-09-01") @Nullable
        String sourceRevision,

        @Schema(description = "Vehicle-match confidence.", example = "EXACT") @Nullable
        MatchGrade matchGrade,

        @Schema(description = "Shared-setup group; lines sharing it must not be summed naively.") @Nullable
        String overlapGroup,

        @Schema(description = "Durion operation codes already included in this time.") @NonNull
        List<String> includedOpCodes,

        @Schema(
                description = "SHOP when the quoting location's own authored time answered, else PLATFORM.",
                example = "PLATFORM")
        @Nullable
        String ownerScope) {

    /** Typed resolution outcomes; misses are statuses, never errors. */
    @Schema(name = "LaborTimeQuoteStatus")
    public enum Status {
        /** A time was found; provenance fields say where it came from. */
        RESOLVED,
        /** Nothing stored, nothing live, no default hours — the writer types the hours. */
        NO_TIME_AVAILABLE,
        /** A live-only source failed and nothing else answered; retrying may succeed. */
        SOURCE_UNAVAILABLE
    }

    /** Vehicle-match confidence, exact-first then widening. */
    @Schema(name = "LaborTimeMatchGrade")
    public enum MatchGrade {
        EXACT,
        ENGINE_WILDCARD,
        MODEL_LEVEL,
        DEFAULT_HOURS
    }

    public static LaborTimeQuoteResponse miss(@NonNull Status status) {
        return new LaborTimeQuoteResponse(status, null, null, null, null, null, null, List.of(), null);
    }
}
