package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.DisplayReferenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One recognized reference inside an accounting event's raw payload, projected with the
 * human-readable identity accounting can offer for it (issues #1778, #1797).
 *
 * <p>The raw payload itself is unchanged and still returned in full — this projection sits
 * alongside it so screens can label a reference without parsing the payload or issuing a
 * cross-domain lookup. Both display values are independently nullable and are null when
 * accounting cannot resolve the reference; an identifier is never copied into a display field as
 * fallback text.
 *
 * <p>{@code path} and {@code rawValue} let a caller correlate an entry back to the exact payload
 * value it describes. {@code id} is the same value parsed as a UUID, for the UUID-backed types
 * that route on one; it is null for a code-valued reference such as an accounting location code,
 * which has no UUID (issue #1797).
 */
@Data
@Builder
@Schema(
        description = "Display projection for one reference found in the event's raw payload. "
                + "Display values are null when unavailable and are never replaced by the identifier.")
public class EventPayloadReference {

    @Schema(
            description = "Dot-separated path of the value inside the raw payload, e.g. "
                    + "\"billDetails.vendorId\". Array elements are indexed, e.g. \"lines[0].locationId\".",
            example = "billDetails.vendorId",
            requiredMode = REQUIRED)
    private String path;

    @Schema(
            description = "Kind of entity the identifier refers to, as recognized from the payload key.",
            example = "VENDOR",
            requiredMode = REQUIRED)
    private DisplayReferenceType referenceType;

    @Schema(
            description = "The identifier as it appears in the raw payload, trimmed. Always present — this "
                    + "is the value the display fields describe, and it correlates the entry back to the "
                    + "payload. A UUID string for UUID-backed types; an accounting location code such as "
                    + "\"LOC-107\" for LOCATION.",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private String rawValue;

    @Schema(
            description = "rawValue parsed as a UUID, for routing and audit. Null when the raw value is "
                    + "not in canonical UUID form — the normal case for LOCATION, whose accounting dimension "
                    + "is a code rather than a UUID.",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private UUID id;

    @Schema(
            description = "Human-readable name for the reference, e.g. a customer display name or a "
                    + "location label. Null when accounting holds no name for it.",
            example = "Northside Fleet Services",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String displayName;

    @Schema(
            description = "Stable business reference or number for the reference, e.g. an invoice number, "
                    + "customer number, journal-entry number or the canonical location code. Null when "
                    + "accounting holds none.",
            example = "C-10427",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String displayReference;
}
