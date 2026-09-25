package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One identifier usable to trace an accounting event across the ingestion pipeline, published
 * under {@link EventEnvelopeContract#getTraceabilityIds()} (issue #2207).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An identifier usable to trace an accounting event across the ingestion pipeline")
public class TraceabilityIdDescriptor {

    @Schema(description = "Name of the identifier", example = "eventId", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "What the identifier is and how it is used", requiredMode = REQUIRED)
    private String description;

    @Schema(
            description = "Where the identifier is carried: a request/response header name, or a field on "
                    + "the ingestion response",
            example = "AccountingEventResponse.eventId",
            requiredMode = REQUIRED)
    private String location;
}
