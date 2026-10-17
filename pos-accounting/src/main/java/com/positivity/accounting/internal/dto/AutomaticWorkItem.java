package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AutomaticWorkItemKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing the system did without a person (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Done automatically").
 * References are business values, never an identifier as text (ADR-0064); identifiers are in {@code routing}.
 */
@Schema(name = "AutomaticWorkItem", description = "Something the system did on its own, with the undo the caller may take")
public record AutomaticWorkItem(
        @Schema(
                description = "Stable identifier: the kind, a colon and the source's identifier",
                example = "SETTLEMENT_LINE_MATCHED:0198a000-0000-7000-8000-000000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String itemId,

        @Schema(description = "What was done", requiredMode = Schema.RequiredMode.REQUIRED) AutomaticWorkItemKind kind,

        @Schema(
                description = "The business date it was done, in the tenant's accounting time zone",
                example = "2026-10-17",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate occurredOn,

        @Schema(
                description = "Business values to name the item, by key; the keys depend on the kind",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, String> references,

        @Schema(
                description = "The amount concerned, in the functional currency",
                example = "250.00",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable
        BigDecimal amount,

        @Schema(description = "The source's status; REVERSED for an application already undone", example = "APPLIED", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,

        @Schema(description = "UNDO for a payment application the caller may reverse; empty otherwise", requiredMode = Schema.RequiredMode.REQUIRED)
        List<WorkItemAction> actions,

        @Schema(description = "Identifiers for routing, by key; the only place an identifier appears", requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, UUID> routing) {}
