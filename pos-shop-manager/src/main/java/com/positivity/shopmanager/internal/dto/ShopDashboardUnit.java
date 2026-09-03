package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One repair unit — a bay or a mobile unit — and what is on it as of the requested date (#1658).
 *
 * <p>The union of the two aggregates is synthesized per request and tagged with
 * {@link ShopDashboardUnitType}; Shop Management persists no unified "Unit" entity, because the two
 * halves belong to pos-location and a persisted union would be a third copy of somebody else's
 * facts.
 *
 * <p>{@code assignment} is explicitly {@code null} for an idle unit rather than the unit being
 * omitted: an empty bay is the single most actionable thing on a shop dashboard, and a reader
 * cannot distinguish "no bay" from "free bay" if free bays are dropped.
 */
@Schema(description = "A bay or mobile unit at the location, with the workorder on it or an explicit null.")
public record ShopDashboardUnit(
        @Schema(
                description = "Bay or mobile-unit identifier, from the owning location domain.",
                example = "01960005-0000-7000-8000-0000000000b1",
                format = "uuid")
        @NonNull
        UUID unitId,

        @Schema(description = "Which kind of repair unit this row describes.", example = "BAY") @NonNull
        ShopDashboardUnitType unitType,

        @Schema(description = "Display name from the location replica; null until the fact arrives.") @Nullable
        String unitName,

        @Schema(description = "The workorder occupying this unit, or null when the unit is free.") @Nullable
        ShopDashboardWorkorder assignment) {}
