package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import io.swagger.v3.oas.annotations.media.Schema;

/** One accounting event type the module records, as listed by {@code GET /v1/accounting/events/types}. */
@Schema(description = "An accounting event type the module records")
public record AccountingEventTypeResponse(
        @Schema(
                description = "Value accepted by the eventType filter of listAccountingEvents",
                example = "inventory.scrap.posted",
                requiredMode = REQUIRED)
        String code,

        @Schema(description = "Human label", example = "Inventory scrap posted", requiredMode = REQUIRED)
        String displayName,

        @Schema(
                description = "Producing domain",
                example = "inventory",
                allowableValues = {"invoice", "order", "inventory", "supplier", "warranty", "payment"},
                requiredMode = REQUIRED)
        String sourceDomain,

        @Schema(description = "How the event type reaches accounting", example = "KAFKA", requiredMode = REQUIRED)
        AccountingEventTypeRegistry.Ingestion ingestion,

        @Schema(
                description = "Whether a fact of this type can produce a journal entry",
                example = "true",
                requiredMode = REQUIRED)
        boolean postsToGl) {

    /** Projects a registry entry. */
    public static AccountingEventTypeResponse from(AccountingEventTypeRegistry.Entry entry) {
        return new AccountingEventTypeResponse(
                entry.code(), entry.displayName(), entry.sourceDomain(), entry.ingestion(), entry.postsToGl());
    }
}
