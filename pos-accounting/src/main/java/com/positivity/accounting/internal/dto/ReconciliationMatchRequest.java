package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to match a set of statement lines to a set of posted GL journal-entry
 * lines (Story F2, issue #965). Supports 1-to-1 and N-to-1; the two sets must net
 * to equal signed amounts within ±0.01.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to match statement lines to GL journal-entry lines")
public class ReconciliationMatchRequest {

    @NotEmpty(message = "statementLineIds is required")
    @Schema(description = "Statement line ids to match", requiredMode = REQUIRED)
    private List<UUID> statementLineIds;

    @NotEmpty(message = "glLineIds is required")
    @Schema(description = "Posted GL journal-entry line ids to match against", requiredMode = REQUIRED)
    private List<UUID> glLineIds;
}
