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
                        + "double-booking reads greater than 1. Work that began on an earlier date (listed "
                        + "in carryOverIn below) is already reflected here, but not always in the same "
                        + "slots: an appointment whose effective window simply runs on into this day marks "
                        + "the hours it really occupies on the clock, while minutes re-anchored from a prior "
                        + "open day's overrun have no clock position of their own and are marked from the "
                        + "start of this day's window (issues #2021 AC5, #2050).",
                example = "[0,0,1,1,1,0]",
                requiredMode = REQUIRED)
        private List<Integer> occupancy;

        @Schema(
                description = "Every appointment holding this bay on this date that did not begin on this "
                        + "date — its effective window opened on an earlier local date, whether it overran a "
                        + "prior open day's close or is simply still running — listed once each, sorted by "
                        + "(fromDate, appointmentId) (issues #2021 AC4/AC5/AC6, #2050). Already netted into "
                        + "occupiedMinutes and occupancy above — this list is the detail behind those "
                        + "numbers, never an addition to them. Populated only when status is OK. The "
                        + "lookback that finds these is bounded at 42 days, the same limit as the requested "
                        + "range, and what it bounds is the planned window: an appointment whose planned "
                        + "window ended more than that far before the range's first date is never fetched, "
                        + "so it cannot be reported here. That bound does not carry over to fromDate, which "
                        + "reports when the work actually began and can therefore be earlier than the "
                        + "lookback reaches. Empty when this bay has no appointments on this date, or when "
                        + "every appointment it does have began on this date.",
                requiredMode = REQUIRED)
        private List<CarryOverView> carryOverIn = new ArrayList<>();
    }

    @Data
    @Schema(
            description = "One earlier-starting appointment's contribution to this bay's capacity on this "
                    + "date — the minutes it holds here because its work began before this date, however "
                    + "those minutes reached the day (issues #2021 AC4/AC5/AC6, #2050)")
    public static class CarryOverView {

        @Schema(
                description = "The local date this appointment's effective window began — the linked "
                        + "workorder's actual start when known, else the appointment's planned start. It "
                        + "names when the work started, which is what lets a board say what is still "
                        + "holding the bay; it is not necessarily the date of an overrun, nor necessarily "
                        + "an open day (#2050).",
                example = "2026-10-10",
                requiredMode = REQUIRED)
        private LocalDate fromDate;

        @Schema(
                description = "The identifier of the appointment holding the bay",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        private UUID appointmentId;

        @Schema(
                description = "The linked workorder identifier this contribution's effective window came "
                        + "from, when known",
                example = "01960003-0000-7000-8000-000000000005",
                requiredMode = NOT_REQUIRED)
        private UUID workorderId;

        @Schema(
                description = "Bay-hours of this date that this appointment accounts for, in tenths of an "
                        + "hour, whichever of two ways they reached it: either its real-clock overlap with "
                        + "this day's window, or the minutes re-anchored onto this day from a prior open "
                        + "day's overrun. The two sources are disjoint by construction — re-anchoring only "
                        + "ever targets days after the last day the appointment directly overlapped — so "
                        + "exactly one of them produced this number (#2050)",
                example = "1.5",
                requiredMode = REQUIRED)
        private BigDecimal bayHours;
    }
}
