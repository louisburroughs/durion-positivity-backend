package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.enums.ShiftSource;
import com.positivity.shopmanager.internal.enums.ShiftStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "A technician with an ACTIVE staffing assignment at the location, from the HR-synchronized mechanic roster")
public class LocationTechnicianRosterEntryResponse {

    private UUID locationId;
    private UUID mechanicId;
    private UUID personId;
    private String firstName;
    private String lastName;
    private MechanicStatus status;
    private LocalDate hireDate;
    private LocalDate terminationDate;
    private Instant lastSyncedAt;
    /**
     * Every credential the person holds, each with its status on the roster's reference date —
     * a facility-local date for the location roster (CAP-328; DECISION-SHOPMGMT-015). Expired,
     * revoked and superseded credentials are listed with that status rather than dropped.
     */
    private List<TechnicianCredentialResponse> credentials;

    // ---- PLACEHOLDER shift window (issue #2060) ---------------------------------------------
    // Derived from the location's operating hours by LocationHoursShiftWindowService, identical
    // for every technician on the roster for the day. Not the person's schedule: see that
    // service's Javadoc for what to delete when the real per-person window (#71) lands.

    @Schema(
            description = "PLACEHOLDER: start of the technician's shift window on the roster date, as a UTC"
                    + " instant. Derived from the shop location's operating hours (its open time in the"
                    + " location's timezone), not from the person's own schedule, so every technician at"
                    + " the location carries the same value. Null when shiftStatus is CLOSED or UNKNOWN.",
            example = "2026-09-17T13:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant shiftStart;

    @Schema(
            description = "PLACEHOLDER: end of the technician's shift window on the roster date, as a UTC"
                    + " instant. Derived from the shop location's operating hours (its close time in the"
                    + " location's timezone), not from the person's own schedule, so every technician at"
                    + " the location carries the same value. Null when shiftStatus is CLOSED or UNKNOWN.",
            example = "2026-09-17T22:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant shiftEnd;

    @Schema(
            description = "PLACEHOLDER: minutes between shiftStart and shiftEnd, for the board to subtract"
                    + " committed time from. Derived from the shop location's operating hours, not from"
                    + " the person's own schedule. Null whenever either bound is null; never negative.",
            example = "540",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer shiftMinutes;

    @Schema(
            description = "PLACEHOLDER: where the shift window came from. LOCATION_HOURS means the window"
                    + " is the shop location's operating hours, the same for every technician there, and"
                    + " not the person's roster. A consumer must read this to tell a placeholder window"
                    + " from a real per-person one; PERSON_SCHEDULE is reserved for that (#71).",
            example = "LOCATION_HOURS",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ShiftSource shiftSource;

    @Schema(
            description = "PLACEHOLDER: whether a shift window could be derived from the shop location's"
                    + " operating hours (not from the person's own schedule). DERIVED carries a window;"
                    + " CLOSED means a dated holiday closure covers the day; UNKNOWN means the location's"
                    + " timezone or hours are missing or unreadable, or the weekday has no entry — no"
                    + " default window is ever substituted.",
            example = "DERIVED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ShiftStatus shiftStatus;
}
