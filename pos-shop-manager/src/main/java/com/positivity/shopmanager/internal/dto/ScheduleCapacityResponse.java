package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.ScheduleCapacityDayStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/**
 * Aggregate bay-occupancy read for a date range at one location (issue #2023, {@code GET
 * /v1/schedules/capacity}).
 *
 * <p>Deliberately not a list of {@code /v1/schedules/view} responses: this shape carries no
 * appointment identifiers, customer snapshots, titles or conflict details (AC2) — only per-day,
 * per-bay, per-hour occupancy counts, which is what the calendar/capacity grid actually renders and
 * is enough to compute free capacity for a bay that has nothing booked in it.
 */
@Data
@Schema(description = "Per-day, per-bay occupancy for a location across a bounded date range")
public class ScheduleCapacityResponse {

    @Schema(
            description = "Location identifier the capacity view belongs to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "First date in the requested range, inclusive (ISO-8601)",
            example = "2026-10-01",
            requiredMode = REQUIRED)
    private LocalDate from;

    @Schema(
            description = "Last date in the requested range, inclusive (ISO-8601)",
            example = "2026-11-11",
            requiredMode = REQUIRED)
    private LocalDate to;

    @Schema(
            description = "IANA timezone id the day windows were computed in, from the location replica. "
                    + "Null when the location's timezone is unknown — never silently UTC (AC11).",
            example = "America/Chicago",
            requiredMode = NOT_REQUIRED)
    private String timezone;

    @Schema(
            description = "Instant this view was generated (ISO-8601)",
            example = "2026-09-16T07:55:00Z",
            requiredMode = REQUIRED)
    private Instant viewGeneratedAt;

    @Schema(description = "One entry per date in [from, to], in order; never omits a date", requiredMode = REQUIRED)
    private List<DayCapacityView> days;

    @Data
    @Schema(description = "Bay occupancy for a single date")
    public static class DayCapacityView {

        @Schema(description = "Calendar date (ISO-8601)", example = "2026-10-04", requiredMode = REQUIRED)
        private LocalDate date;

        @Schema(description = "Assembly outcome for this date", example = "OK", requiredMode = REQUIRED)
        private ScheduleCapacityDayStatus status;

        @Schema(
                description = "Reason recorded against the holiday closure covering this date; set only "
                        + "when status is HOLIDAY",
                example = "Thanksgiving",
                requiredMode = NOT_REQUIRED)
        private String closureReason;

        @Schema(
                description = "Start of this date's operating window, from this date's own hours entry. "
                        + "Null unless status is OK.",
                example = "2026-10-04T13:00:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant dayStartAt;

        @Schema(
                description = "End of this date's operating window, from this date's own hours entry. "
                        + "Null unless status is OK.",
                example = "2026-10-04T22:00:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant dayEndAt;

        @Schema(
                description = "Every active bay at the location with its occupancy for this date. Empty "
                        + "unless status is OK — a bay with zero appointments is still listed, with "
                        + "occupiedMinutes 0 (AC9).",
                requiredMode = REQUIRED)
        private List<BayCapacityView> bays;
    }

    @Data
    @Schema(description = "One bay's occupancy on one date")
    public static class BayCapacityView {

        @Schema(
                description = "Bay identifier",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = REQUIRED)
        private UUID bayId;

        @Schema(description = "Display name of the bay", example = "Bay 1", requiredMode = NOT_REQUIRED)
        private String name;

        @Schema(
                description = "Total minutes of the day's window occupied by appointments in this bay "
                        + "(the real overlap, not slots * 60; CANCELLED appointments never occupy a bay)",
                example = "240",
                requiredMode = REQUIRED)
        private int occupiedMinutes;

        @Schema(
                description = "One slot per hour of the day's window (a partial trailing hour still gets a "
                        + "slot); each value is the count of appointments overlapping that hour, so a "
                        + "double-booking reads greater than 1. Carry-over from a prior open day (below) is "
                        + "already reflected here, marked from the start of the window (issue #2021 AC5).",
                example = "[0,0,1,1,1,0]",
                requiredMode = REQUIRED)
        private List<Integer> occupancy;

        @Schema(
                description = "Bay-hours carried into this day from an appointment that overran a prior "
                        + "open day's close (issue #2021 AC4/AC5/AC6). Already netted into occupiedMinutes "
                        + "and occupancy above — this list is the detail behind that number, not an addition "
                        + "to it. Empty when nothing carried over.",
                requiredMode = REQUIRED)
        private List<CarryOverView> carryOverIn = new ArrayList<>();
    }

    @Data
    @Schema(
            description = "One appointment's overrun, carried from a prior open day into this bay's "
                    + "capacity on this date (issue #2021 AC4/AC5/AC6)")
    public static class CarryOverView {

        @Schema(
                description = "The date the appointment actually overran its own operating-day close",
                example = "2026-10-10",
                requiredMode = REQUIRED)
        private LocalDate fromDate;

        @Schema(
                description = "The overrunning appointment's identifier",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        private UUID appointmentId;

        @Schema(
                description = "The linked workorder identifier the overrun comes from, when known",
                example = "01960003-0000-7000-8000-000000000005",
                requiredMode = NOT_REQUIRED)
        private UUID workorderId;

        @Schema(
                description = "Bay-hours carried into this date from the overrun above, in tenths of an hour",
                example = "1.5",
                requiredMode = REQUIRED)
        private BigDecimal bayHours;
    }
}
