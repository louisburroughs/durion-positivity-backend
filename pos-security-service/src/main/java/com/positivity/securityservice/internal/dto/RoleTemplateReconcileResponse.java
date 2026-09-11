package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * What one run of {@code reconcileTemplate(tenant)} changed (ADR-0062 §6, plan WS8). A run that
 * finds the tenant already up to the template reports three empty lists.
 */
@Schema(
        description = "What reconciling the platform role template into a tenant changed; all lists empty when"
                + " the tenant was already up to the template")
public record RoleTemplateReconcileResponse(
        @Schema(description = "The reconciled tenant", example = "01990000-0000-7000-8000-000000000123") @NonNull
        UUID tenantId,

        @Schema(
                description = "Template roles the tenant lacked, created with the template's grants",
                example = "[\"WARRANTY_CLERK\"]")
        @NonNull
        List<String> rolesCreated,

        @Schema(description = "Grants the template carries that an existing role of the tenant lacked, now added")
        @NonNull
        List<GrantAdded> grantsAdded,

        @Schema(
                description = "Existing roles of the tenant that carried no template key and now do",
                example = "[\"SHOP_MANAGER\"]")
        @NonNull
        List<String> templateKeysAssigned) {

    /** One grant added to one role. */
    @Schema(description = "One permission added to one role of the tenant")
    public record GrantAdded(
            @Schema(description = "Role name", example = "SHOP_MANAGER") @NonNull
            String role,

            @Schema(description = "Permission name", example = "warranty:claim:view") @NonNull
            String permission) {}
}
