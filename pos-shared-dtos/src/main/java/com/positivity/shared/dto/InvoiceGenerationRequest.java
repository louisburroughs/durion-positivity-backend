package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Request to generate an invoice from a completed workorder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for generating an invoice from a completed workorder.")
public class InvoiceGenerationRequest {

    @Nullable
    @Schema(description = "Workorder identifier to generate invoice for.", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;
}
