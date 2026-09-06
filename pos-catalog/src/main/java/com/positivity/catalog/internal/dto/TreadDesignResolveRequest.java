package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.enums.TreadDesignResolutionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A reviewer's ruling on a tread design (#1645).
 *
 * <p>Which fields are required depends on the action, and the service rejects the invalid
 * combinations rather than quietly ignoring a field: an ATTACH with no products would otherwise
 * report success while attaching nothing, which is the failure a reviewer is least likely to notice.
 *
 * @param action what to do
 * @param productIds products to attach; required for ATTACH, must be absent or empty otherwise
 * @param note free-text reasoning stored with the design; optional for every action
 * @param deferUntil when a DEFER should return to the worklist; optional, and only for DEFER
 */
@Schema(description = "A reviewer's decision about a tread design awaiting review.")
public record TreadDesignResolveRequest(
        @Schema(description = "ATTACH, REJECT or DEFER.", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        TreadDesignResolutionAction action,

        @Schema(description = "Products to attach. Required for ATTACH, rejected for the other actions.") @Nullable
        List<UUID> productIds,

        @Schema(description = "Why. Stored with the design and shown in the worklist.") @Nullable
        String note,

        @Schema(description = "When a deferred design should come back. DEFER only.") @Nullable
        Instant deferUntil) {}
