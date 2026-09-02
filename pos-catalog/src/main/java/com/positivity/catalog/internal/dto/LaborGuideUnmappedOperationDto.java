package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "A vendor operation code awaiting curation: the feed carried it, no xref maps it."
                + " Draining this queue is deliberate mapping work, never automatic.")
public class LaborGuideUnmappedOperationDto {

    @Schema(description = "Source that published the code", example = "MOCKGUIDE", requiredMode = REQUIRED)
    private String sourceCode;

    @Schema(description = "The vendor's operation code", example = "MG-FOG-LAMP-ALIGN", requiredMode = REQUIRED)
    private String providerOpCode;

    @Schema(description = "Times the code has been seen across imports", requiredMode = REQUIRED)
    private long occurrenceCount;

    @Schema(description = "Import that most recently carried it", requiredMode = NOT_REQUIRED)
    private UUID lastManifestId;

    @Schema(requiredMode = REQUIRED)
    private Instant firstSeenAt;

    @Schema(requiredMode = REQUIRED)
    private Instant lastSeenAt;
}
