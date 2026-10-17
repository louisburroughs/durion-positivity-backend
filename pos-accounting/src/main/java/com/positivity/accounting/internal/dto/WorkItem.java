package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.WorkItemArea;
import com.positivity.accounting.internal.enums.WorkItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One to-do item (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items"): built on read from its source and
 * already filtered to what the caller may act on. References are business values, never an identifier as text
 * (ADR-0064); the identifiers are in {@code routing}, for the client to open the item.
 */
@Schema(name = "WorkItem", description = "A thing that needs a person, with the actions the caller may take on it")
public record WorkItem(
        @Schema(
                description = "Stable identifier: the type, a colon and the source's identifier",
                example = "BILL_AWAITING_APPROVAL:0198a000-0000-7000-8000-000000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String itemId,

        @Schema(description = "The kind of item", requiredMode = Schema.RequiredMode.REQUIRED)
        WorkItemType type,

        @Schema(description = "The area the item is listed under", requiredMode = Schema.RequiredMode.REQUIRED)
        WorkItemArea area,

        @Schema(
                description = "Business values to name the item, by key: vendor, bill number, purchase order number,"
                        + " customer, payment reference, invoice number, location, terminal, bag numbers, statement"
                        + " month and the like; the keys depend on the type",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, String> references,

        @Schema(
                description = "Why the item is listed, a code",
                example = "OVERPAID",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reasonCode,

        @Schema(
                description = "The amount the item concerns, in the functional currency; null when it has none",
                example = "1197.00",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        BigDecimal amount,

        @Schema(
                description = "The source's status",
                example = "AWAITING_APPROVAL",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status,

        @Schema(
                description = "The business date the item dates from, in the tenant's accounting time zone",
                example = "2026-10-12",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate occurredOn,

        @Schema(
                description = "What the caller may do, each allowed or blocked with its reason",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<WorkItemAction> actions,

        @Schema(
                description = "The entry the item's posting action would make; empty where no action posts",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<WorkItemPostingLine> postingPreview,

        @Schema(
                description = "Identifiers for routing to the item's screen, by key; the only place an identifier"
                        + " appears",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, UUID> routing) {}
