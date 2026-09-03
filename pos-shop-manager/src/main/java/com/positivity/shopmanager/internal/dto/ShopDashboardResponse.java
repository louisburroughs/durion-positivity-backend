package com.positivity.shopmanager.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * The whole shop dashboard for one location, answered in one call (#1658).
 *
 * <p>{@code units} and {@code openWorkorders} are two different questions, not two views of one
 * list. {@code units} is the physical roster — every bay and mobile unit at the site, each with the
 * job on it as of {@code date} or an explicit null. {@code openWorkorders} is every open job at the
 * site whatever its assignment, so it is a <em>superset</em>: it also contains the jobs that are on
 * no unit at all. Only {@code units} is date-scoped.
 */
@Schema(description = "Bays, mobile units and open workorders for one shop location in a single read.")
public record ShopDashboardResponse(
        @Schema(
                description = "The location this dashboard describes.",
                example = "018e1c9f-6b5a-7890-abcd-1234567890ab",
                format = "uuid")
        @NonNull
        UUID locationId,

        @Schema(
                description = "The day the unit roster is rendered as of, in the location's local calendar.",
                example = "2026-09-03",
                format = "date")
        @NonNull
        LocalDate date,

        @Schema(description = "Every bay and mobile unit at the location, idle ones included.") @NonNull
        List<ShopDashboardUnit> units,

        @Schema(description = "Every open workorder at the location, assigned or not. Not date-scoped.") @NonNull
        List<ShopDashboardWorkorder> openWorkorders,

        @Schema(
                description = "True when openWorkorders was cut at the 200-row cap and more rows exist.",
                example = "false")
        boolean openWorkordersTruncated) {}
