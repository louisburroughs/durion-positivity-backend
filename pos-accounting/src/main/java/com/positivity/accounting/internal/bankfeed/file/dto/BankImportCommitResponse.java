package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The outcome of a commit (SPEC §4.4, §6.1; story S3, #2302); a second commit answers the same. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Outcome of an import commit")
public class BankImportCommitResponse {

    @Schema(description = "The import", requiredMode = REQUIRED)
    private UUID importId;

    @Schema(
            description = "The committed statement (the last segment's when the window was split)",
            requiredMode = REQUIRED)
    private UUID statementId;

    @Schema(
            description = "Every committed statement in window order: one, or one per split segment",
            requiredMode = REQUIRED)
    private List<UUID> statementIds;

    @Schema(description = "Bank transactions created", example = "240", requiredMode = REQUIRED)
    private Integer bankTransactionCount;

    @Schema(
            description = "Of which entered as POSSIBLE_DUPLICATE for later review",
            example = "0",
            requiredMode = REQUIRED)
    private Integer possibleDuplicateCount;

    @Schema(description = "The reconciliation started with the commit; null until story S4 wires it")
    private UUID reconciliationId;

    @Schema(description = "The import version after the commit", example = "4", requiredMode = REQUIRED)
    private Long version;
}
