package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request for a dry-run posting rule / mapping resolution (story E3, issue
 * #957).
 *
 * Describes a hypothetical accounting event: the event type, an optional
 * sample payload, and the transaction date used for effective-dated rule
 * version and mapping selection. Nothing about this request is persisted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Dry-run mapping/rule resolution request describing a hypothetical accounting event. "
                + "Nothing is persisted by this operation.")
public class MappingResolutionTestRequest {

    @NotBlank(message = "Event type is required")
    @Schema(
            description = "Accounting event type to resolve posting rules for",
            example = "INVOICE_FINALIZED",
            requiredMode = REQUIRED)
    private String eventType;

    @Nullable
    @Schema(
            description = "Sample event payload (arbitrary JSON object) evaluated against rule predicates "
                    + "and amount fields. Optional; when omitted the rules are evaluated against an empty payload.",
            example = "{\"totalAmount\": 125.00, \"channel\": \"POS\"}",
            requiredMode = NOT_REQUIRED)
    private Map<String, Object> samplePayload;

    @NotNull(message = "Transaction date is required")
    @Schema(
            description = "Transaction date used for effective-dated rule version and GL mapping selection",
            example = "2026-01-15",
            requiredMode = REQUIRED)
    private LocalDate transactionDate;
}
