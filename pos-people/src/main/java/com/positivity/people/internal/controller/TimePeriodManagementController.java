package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateTimePeriodRequest;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TransitionTimePeriodRequest;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.TimePeriodManagementService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people/time-periods")
@Tag(name = "Time Period Management API", description = "Pay-period lifecycle management for timekeeping approval")
public class TimePeriodManagementController {

    private final TimePeriodManagementService timePeriodManagementService;

    @PostMapping
    @Operation(operationId = "createTimePeriod", summary = "Create Pay Period", description = """
                    Creates a pay period for a tenant with an inclusive start and end date and an \
                    initial lifecycle status.
                    Use this tool for corrections and off-grid periods; do not create routine periods \
                    manually, the scheduled rollover opens those on the configured cadence instead.
                    Preconditions: the range must not overlap any existing period for the tenant, and \
                    endDate must not be before startDate.
                    Required inputs: a body with tenantId (UUID), startDate, and endDate; status is \
                    optional and defaults to OPEN.
                    Emits PEOPLE_TIME_PERIOD_CREATE.
                    Returns 400 when the range is invalid, and 409 when the range overlaps an existing \
                    period.
                    """)
    @ApiResponse(responseCode = "201", description = "Time period created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid date range",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Overlapping period exists",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timePeriod:create"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEPERIOD_CREATE + "')")
    @EmitEvent(id = "PEOPLE_TIME_PERIOD_CREATE", apiVersion = "1")
    public ResponseEntity<TimePeriodDto> createTimePeriod(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Tenant, inclusive date range, and optional initial status of the new" + " period.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Biweekly period", value = """
                                                    {"tenantId":"01960000-0000-7000-8000-000000000001",\
                                                    "startDate":"2026-06-01","endDate":"2026-06-14"}
                                                    """)))
                    @RequestBody
                    @Valid
                    CreateTimePeriodRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(timePeriodManagementService.createTimePeriod(request));
    }

    @PostMapping("/{timePeriodId}/status")
    @Operation(operationId = "transitionTimePeriod", summary = "Transition Pay Period Status", description = """
                    Moves a pay period to a new lifecycle status along the allowed transitions, \
                    including reopening a SUBMISSION_CLOSED period for corrections.
                    Use this tool for manual corrections; do not drive routine closure with it, the \
                    scheduled rollover advances statuses on the configured cadence instead.
                    Preconditions: allowed transitions are OPEN to SUBMISSION_CLOSED or PAYROLL_CLOSED, \
                    SUBMISSION_CLOSED to PAYROLL_CLOSED or back to OPEN; PAYROLL_CLOSED is terminal.
                    Required inputs: timePeriodId (UUID) path parameter and a body with the target \
                    status.
                    Emits PEOPLE_TIME_PERIOD_TRANSITION.
                    Returns 404 when the period does not exist, and 409 when the transition is not \
                    allowed.
                    """)
    @ApiResponse(responseCode = "200", description = "Time period transitioned")
    @ApiResponse(
            responseCode = "404",
            description = "Time period not found",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Transition not allowed",
            content =
                    @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:timePeriod:transition"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.TIMEPERIOD_TRANSITION + "')")
    @EmitEvent(id = "PEOPLE_TIME_PERIOD_TRANSITION", apiVersion = "1")
    public ResponseEntity<TimePeriodDto> transitionTimePeriod(
            @PathVariable UUID timePeriodId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Target lifecycle status for the period.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Close submissions", value = """
                                                    {"status":"SUBMISSION_CLOSED"}
                                                    """)))
                    @RequestBody
                    @Valid
                    TransitionTimePeriodRequest request) {
        return ResponseEntity.ok(timePeriodManagementService.transitionTimePeriod(timePeriodId, request.getStatus()));
    }
}
