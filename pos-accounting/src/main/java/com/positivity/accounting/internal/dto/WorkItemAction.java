package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One action a work item offers the caller (CAP:550 S19, #2515; P5): allowed, or shown disabled with its reason. */
@Schema(name = "WorkItemAction", description = "An action the caller may take on a work item, or see blocked with why")
public record WorkItemAction(
        @Schema(description = "The action's code", example = "APPROVE", requiredMode = Schema.RequiredMode.REQUIRED)
        String action,

        @Schema(
                description = "False when a rule blocks this caller; the action is then shown disabled",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean allowed,

        @Schema(
                description = "The code of the rule that blocks the caller; null when allowed",
                example = "AP_BILL_SELF_APPROVAL",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String blockedReason) {

    /** An action the caller may take. */
    public static WorkItemAction allowed(String action) {
        return new WorkItemAction(action, true, null);
    }

    /** An action a rule blocks for the caller. */
    public static WorkItemAction blocked(String action, String reason) {
        return new WorkItemAction(action, false, reason);
    }
}
