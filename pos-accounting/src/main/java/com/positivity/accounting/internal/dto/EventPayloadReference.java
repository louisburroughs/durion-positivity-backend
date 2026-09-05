package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.DisplayReferenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One recognized UUID-backed value inside an accounting event's raw payload, projected with the
 * human-readable identity accounting can offer for it (issue #1778).
 *
 * <p>The raw payload itself is unchanged and still returned in full — this projection sits
 * alongside it so screens can label a reference without parsing the payload or issuing a
 * cross-domain lookup. Both display values are independently nullable and are null when
 * accounting cannot resolve the reference; a UUID is never copied into a display field as
 * fallback text. {@code path} and {@code id} let a caller correlate an entry back to the exact
 * payload value it describes, and remain available for routing and diagnostics.
 */
@Data
@Builder
@Schema(
        description = "Display projection for one UUID-backed value found in the event's raw payload. "
                + "Display values are null when unavailable and are never replaced by the UUID.")
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
            description = "The identifier as it appears in the raw payload. Always present — this is the "
                    + "value the display fields describe, and it stays available for routing and audit.",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
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
                    + "customer number or journal-entry number. Null when accounting holds none.",
            example = "C-10427",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String displayReference;
}
