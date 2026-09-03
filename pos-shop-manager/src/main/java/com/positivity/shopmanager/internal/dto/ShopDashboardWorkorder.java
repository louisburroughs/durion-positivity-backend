package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One open workorder on the shop dashboard (#1658).
 *
 * <p>{@code unitId} / {@code unitName} / {@code unitType} are populated when the workorder is
 * assigned to a bay or mobile unit and are {@code null} when it is not — a DRAFT job that nobody
 * has dispatched yet is open-but-unassigned, which is a normal state and not a data defect.
 */
@Schema(description = "An open workorder at the location, with its unit assignment when it has one.")
public record ShopDashboardWorkorder(
        @Schema(
                description = "Workorder identifier.",
                example = "01960003-0000-7000-8000-000000000002",
                format = "uuid")
        @NonNull
        UUID workorderId,

        @Schema(description = "Human-readable workorder number.", example = "WO-2026-1001") @Nullable
        String workorderNumber,

        @Schema(description = "Owner's workorder status name.", example = "WORK_IN_PROGRESS") @Nullable
        String status,

        @Schema(
                description = "Bay or mobile unit the workorder occupies; null when unassigned.",
                example = "01960005-0000-7000-8000-0000000000b1",
                format = "uuid")
        @Nullable
        UUID unitId,

        @Schema(description = "Display name of the occupied unit; null when unassigned.", example = "Front Bay 1")
        @Nullable
        String unitName,

        @Schema(description = "Kind of unit occupied; null when unassigned.", example = "BAY") @Nullable
        ShopDashboardUnitType unitType,

        @Schema(description = "Serviced vehicle; null when the replica has no row for it yet.") @Nullable
        ShopDashboardVehicle vehicle,

        @Schema(description = "Display name of the first assigned mechanic.", example = "Ada Lovelace") @Nullable
        String mechanicName,

        @Schema(description = "Display names of every assigned mechanic, in assignment order.") @NonNull
        List<String> mechanicNames,

        @Schema(
                description =
                        "Time the vehicle is promised back; always null until pos-workorder owns" + " a promise time.",
                example = "2026-09-03T17:00:00Z",
                format = "date-time")
        @Nullable
        Instant promisedAt) {}
