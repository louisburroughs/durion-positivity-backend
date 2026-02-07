package com.positivity.accounting.internal.dto;

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
public class JournalEntryReversalRequest {

    @NotBlank(message = "Reversal reason is required")
    private String reason;
}
