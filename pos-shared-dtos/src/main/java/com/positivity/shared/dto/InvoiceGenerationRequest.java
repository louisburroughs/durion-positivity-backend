package com.positivity.shared.dto;

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
public class InvoiceGenerationRequest {

    @Nullable
    private UUID workorderId;
}
