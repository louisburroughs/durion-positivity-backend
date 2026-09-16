package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.AbsenceScope;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.NoOpeningReason;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.OpeningConstraint;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.SkillFulfillment;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.StaffingAdvisoryCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Ranked, bookable openings for one job at one location ({@code GET /v1/schedules/openings},
 * #2022). Advisory by the domain's own contract (DECISION-SHOPMGMT-011): the submit-time tier
 * decides, and every opening says what it evaluated (spec D11).
 */
@Data
@Schema(description = "Duration-aware eligible openings for a job at a location, earliest first")
public class OpeningSearchResponse {

    @Schema(
            description = "Location searched",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "IANA timezone the day windows were computed in, from the location replica",
            example = "America/New_York",
            requiredMode = REQUIRED)
    private String timezone;

    @Schema(description = "Catalog service ids the job consists of", requiredMode = REQUIRED)
    private List<UUID> serviceIds = new ArrayList<>();

    @Schema(description = "Requested job duration in minutes", example = "90", requiredMode = REQUIRED)
    private int durationMinutes;

    @Schema(
            description = "Start of the searched horizon (the earliestStart requested, ISO-8601)",
            requiredMode = REQUIRED)
    private Instant searchedFrom;

    @Schema(
            description = "Exclusive end of the searched horizon: earliestStart plus horizonDays (ISO-8601)",
            requiredMode = REQUIRED)
    private Instant searchedTo;

    @Schema(
            description = "Vehicle GVWR class (1-8) the requirements were resolved for; null when no vehicle was given"
                    + " or its class is undetermined, in which case only ANY-class requirements applied",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private Integer vehicleGvwrClass;

    @Schema(
            description = "Skill codes the job requires for this vehicle class, from the catalog replica",
            requiredMode = REQUIRED)
    private List<String> requiredSkillCodes = new ArrayList<>();

    @Schema(description = "How the location's active bays were narrowed to the eligible set", requiredMode = REQUIRED)
    private BayEligibility bayEligibility;

    @Schema(
            description = "Openings ranked earliest start first; CERTIFIED before AWAITING at equal starts",
            requiredMode = REQUIRED)
    private List<Opening> openings = new ArrayList<>();

    @Schema(
            description = "Why openings is empty. Null whenever openings is non-empty, and null when the list is empty"
                    + " solely because no technician is rostered (see staffingAdvisory)",
            requiredMode = NOT_REQUIRED)
    private NoOpeningReason noOpeningReason;

    @Schema(
            description = "Response-level staffing condition invariant across the search; null when every required"
                    + " skill is held by somebody rostered here in the horizon",
            requiredMode = NOT_REQUIRED)
    private StaffingAdvisory staffingAdvisory;

    @Schema(description = "Instant this search was computed (ISO-8601)", requiredMode = REQUIRED)
    private Instant generatedAt;

    /** How many of the location's bays could host this job at all (spec §8: capability and duty class are distinct misses). */
    @Data
    @Builder
    @Schema(description = "Bay eligibility summary for the requested services and vehicle")
    public static class BayEligibility {
        @Schema(description = "Active bays at the location", requiredMode = REQUIRED)
        private int activeBays;

        @Schema(
                description = "Bays eligible for every requested service and the vehicle's duty class",
                requiredMode = REQUIRED)
        private int eligibleBays;

        @Schema(
                description =
                        "Bays excluded because a specialty bay claims one of the operations and this bay does not",
                requiredMode = REQUIRED)
        private int excludedByCapability;

        @Schema(
                description = "Bays excluded because their maxDutyClass is below the vehicle's GVWR class",
                requiredMode = REQUIRED)
        private int excludedByDutyClass;
    }

    /** One bookable window: a bay and the technician it depends on, with what was checked. */
    @Data
    @Builder
    @Schema(description = "One opening: bay, technician, and the constraints actually evaluated")
    public static class Opening {
        @Schema(description = "Window start (ISO-8601); the check-in buffer precedes it", requiredMode = REQUIRED)
        private Instant startAt;

        @Schema(description = "Window end (ISO-8601); the cleanup buffer follows it", requiredMode = REQUIRED)
        private Instant endAt;

        @Schema(description = "Facility-local date of the opening", example = "2026-10-04", requiredMode = REQUIRED)
        private String localDate;

        @Schema(description = "Bay the whole duration is free in", requiredMode = REQUIRED)
        private UUID bayId;

        @Schema(description = "Bay display name", example = "Bay 3", requiredMode = NOT_REQUIRED)
        private String bayName;

        @Schema(
                description = "Technician (person id) rostered that day and free in the window",
                requiredMode = REQUIRED)
        private UUID technicianId;

        @Schema(
                description = "Grain of the roster check. DAY: rostered is day-grain from the staffing replica, busy is"
                        + " minute-grain from appointments; PTO is not modelled",
                example = "DAY",
                requiredMode = REQUIRED)
        private String technicianRosterGrain;

        @Schema(
                description = "Whether the named technician holds every required skill on this date",
                requiredMode = REQUIRED)
        private SkillFulfillment skillFulfillment;

        @Schema(
                description = "Required skill codes the named technician does not hold; empty when CERTIFIED",
                requiredMode = REQUIRED)
        private List<String> unmetSkillCodes;

        @Schema(description = "Constraints this opening actually evaluated (D11)", requiredMode = REQUIRED)
        private List<OpeningConstraint> constraintsEvaluated;
    }

    /** The window-invariant staffing fact, reported once (D10.2). */
    @Data
    @Builder
    @Schema(description = "Response-level staffing advisory")
    public static class StaffingAdvisory {
        @Schema(description = "Condition code, shared with conflict_rule.code", requiredMode = REQUIRED)
        private StaffingAdvisoryCode code;

        @Schema(
                description = "Required skill codes nobody rostered here holds; empty for MECHANIC_UNAVAILABLE",
                requiredMode = REQUIRED)
        private List<String> missingSkillCodes;

        @Schema(description = "Which remedy applies", requiredMode = REQUIRED)
        private AbsenceScope absenceScope;
    }
}
