package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One {@link AccountingEventStatus} constant and its meaning, published under {@link
 * ProcessingStatusesContract#getStatuses()} (issue #2207). Built from {@code
 * AccountingEventStatus.values()} and {@link AccountingEventStatus#meaning()} — never a
 * hand-typed list — so a new status constant cannot be forgotten in the published contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One accounting event processing status and its meaning")
public class ProcessingStatusDescriptor {

    @Schema(description = "The status constant", example = "PROCESSED", requiredMode = REQUIRED)
    private AccountingEventStatus status;

    @Schema(
            description = "Human-readable meaning of the status",
            example = "Event has been successfully processed; a journal entry was created.",
            requiredMode = REQUIRED)
    private String meaning;
}
