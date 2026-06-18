package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for journal entry reversal request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for reversing a journal entry")
public class JournalEntryReversalRequest {

    @NotBlank(message = "Reversal reason is required")
    @Schema(
            description = "Reason for reversing the journal entry",
            example = "Correcting duplicate posting",
            requiredMode = REQUIRED)
    private String reason;
}
