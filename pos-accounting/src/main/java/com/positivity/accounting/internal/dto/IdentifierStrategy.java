package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Additive section of {@link EventEnvelopeContract} (issue #2207) describing how accounting
 * assigns and accepts the identifiers carried on an event envelope: AD-006 / ADR-0013 UUIDv7 for
 * every primary id, {@code eventId} minted server-side unless the caller supplies one, and
 * {@code domainKeyId} as an opaque, upstream-owned string.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "How accounting assigns and accepts the identifiers carried on an event envelope")
public class IdentifierStrategy {

    @Schema(
            description = "Primary-key format for every identifier accounting mints (AD-006 / ADR-0013)",
            example = "UUIDv7",
            requiredMode = REQUIRED)
    private String idFormat;

    @Schema(
            description = "How eventId is assigned: minted server-side (AccountingEvent.eventId, @UUIDv7Id) "
                    + "unless the caller supplies eventId in the payload, which is then accepted verbatim",
            example = "SERVER_UNLESS_SUPPLIED",
            requiredMode = REQUIRED)
    private String eventIdMintedBy;

    @Schema(
            description = "Format of domainKeyId: the upstream domain's own key, accepted as an opaque "
                    + "string and never required to be a UUID",
            example = "OPAQUE_STRING",
            requiredMode = REQUIRED)
    private String domainKeyIdFormat;

    @Schema(description = "Additional notes on identifier handling", requiredMode = NOT_REQUIRED)
    private List<String> notes;
}
