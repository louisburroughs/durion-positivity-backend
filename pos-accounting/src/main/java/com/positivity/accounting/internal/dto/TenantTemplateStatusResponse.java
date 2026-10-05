package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.TemplateEntryKind;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where the caller's tenant stands against the accounting template (#2526): the chart of accounts,
 * posting categories, mapping keys, GL mappings and statement lines every tenant receives.
 */
@Schema(description = "Where the caller's tenant stands against the accounting template every tenant receives")
public record TenantTemplateStatusResponse(
        @Schema(
                description = "NOT_PROVISIONED: the template has never been applied. UP_TO_DATE: everything is"
                        + " created or adopted. PENDING: the template is newer than the last apply and the next"
                        + " service start brings it in. NEEDS_ATTENTION: at least one entry is in conflict or"
                        + " withheld; see attention",
                example = "UP_TO_DATE",
                requiredMode = REQUIRED)
        TenantTemplateState state,

        @Schema(
                description = "When the template was last applied to this tenant; absent when never",
                example = "2026-10-05T14:03:22Z",
                nullable = true)
        @Nullable
        Instant lastAppliedAt,

        @Schema(description = "How many template entries ended in each outcome", requiredMode = REQUIRED)
        Counts counts,

        @Schema(
                description = "True when this tenant has chosen the retread-plant add-on",
                example = "false",
                requiredMode = REQUIRED)
        boolean retreadPlantAddOn,

        @Schema(
                description = "The entries that wait for someone to resolve a clash, in business words; empty"
                        + " unless state is NEEDS_ATTENTION",
                requiredMode = REQUIRED)
        List<AttentionItem> attention) {

    /** How many template entries ended in each outcome. */
    @Schema(
            name = "TenantTemplateCounts",
            description = "How many template entries ended in each outcome for this tenant")
    public record Counts(
            @Schema(description = "Rows the template created", example = "184", requiredMode = REQUIRED)
            int created,

            @Schema(
                    description = "Rows the tenant already held, left exactly as they were",
                    example = "0",
                    requiredMode = REQUIRED)
            int adopted,

            @Schema(
                    description = "Untouched statement lines that took the template's new line code,"
                            + " description and display order",
                    example = "0",
                    requiredMode = REQUIRED)
            int refreshed,

            @Schema(
                    description =
                            "Accounts the tenant holds under a template code that are not the template's" + " account",
                    example = "0",
                    requiredMode = REQUIRED)
            int conflict,

            @Schema(
                    description = "Entries not created because an account they refer to is in conflict or" + " missing",
                    example = "0",
                    requiredMode = REQUIRED)
            int withheld) {}

    /** One entry that needs attention. */
    @Schema(
            name = "TenantTemplateAttentionItem",
            description = "One template entry that waits for the tenant to resolve a clash")
    public record AttentionItem(
            @Schema(
                    description = "The entry's natural key: its kind and what identifies it within the kind",
                    example = "ACCOUNT:6295",
                    requiredMode = REQUIRED)
            String entryKey,

            @Schema(description = "The kind of entry", example = "ACCOUNT", requiredMode = REQUIRED)
            TemplateEntryKind kind,

            @Schema(
                    description = "ACCOUNT_DIFFERS: the tenant's account under this code has another name or"
                            + " type. ACCOUNT_INACTIVE: it matches but is not active. ACCOUNT_MISSING: the"
                            + " entry refers to an account code the tenant does not hold."
                            + " DEPENDS_ON_CONFLICT: the entry refers to an account that is in conflict",
                    example = "ACCOUNT_DIFFERS",
                    requiredMode = REQUIRED)
            TemplateEntryReason reason,

            @Schema(
                    description = "What the template holds, in business words",
                    example = "6295 Staff Meals & Refreshments, expense",
                    requiredMode = REQUIRED)
            String templateValue,

            @Schema(
                    description = "What the tenant holds in its place, in business words; absent when the"
                            + " tenant holds nothing",
                    example = "6295 Tire disposal, expense",
                    nullable = true)
            @Nullable
            String tenantValue) {}
}
