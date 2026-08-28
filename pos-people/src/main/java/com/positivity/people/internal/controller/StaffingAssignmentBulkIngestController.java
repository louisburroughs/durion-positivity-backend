package com.positivity.people.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentBulkIngestRecord;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.EmployeeService;
import com.positivity.people.internal.service.StaffingAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assigns people to locations in bulk.
 *
 * <p>Rows name their person by employee number rather than by id, and it is resolved here: employee
 * numbers are this service's own key, so looking them up locally is cheaper and more reliable than
 * making the caller round-trip for each one.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"people:employee:edit"})
@RequestMapping("/v1/people/staffing")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Staffing Assignment Bulk Ingest API", description = "Bulk import person-to-location assignments")
public class StaffingAssignmentBulkIngestController
        extends AbstractBulkIngestController<StaffingAssignmentBulkIngestRecord> {

    private static final String INGEST_FAILED = "STAFFING_ASSIGNMENT_INGEST_FAILED";
    private static final String UNKNOWN_EMPLOYEE = "STAFFING_ASSIGNMENT_EMPLOYEE_UNKNOWN";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"employeeNumber":"EMP-0001","role":"LOCATION_MANAGER","primary":true},
               {"employeeNumber":"EMP-0002","role":"TECHNICIAN","primary":true}
             ]}
            """;

    private final StaffingAssignmentService staffingAssignmentService;
    private final EmployeeService employeeService;
    private final Clock clock;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_EDIT + "')")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestStaffingAssignments",
            summary = "Assign People to Locations in Bulk",
            description = """
                    Creates many staffing assignments at once, resolving each row's employee number to a person.
                    Use this tool when standing up a location's roster or seeding an environment; use \
                    createStaffingAssignment instead for a single assignment.
                    Preconditions: each employee number must resolve to a person with an ACTIVE employee record, \
                    each location must be active, and no assignment may overlap an existing one for the same \
                    person, location and role.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with an employeeNumber and \
                    a role; a record's own locationId overrides the batch one, and effectiveFrom defaults to today.
                    Emits a PEOPLE_STAFFING_ASSIGNMENT_BULK_INGEST event and a staffing-assignment fact per row.
                    Note two side effects that make this not simply additive: a new primary demotes and end-dates \
                    an overlapping existing primary, and a person's first active assignment is forced primary \
                    whatever the flag says.
                    Returns 200 with a per-record result; check each result rather than the status alone.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Batch processed; inspect per-record results",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BulkIngestResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Staffing assignments to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Location roster",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<StaffingAssignmentBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(
            @NonNull BulkIngestRequest<StaffingAssignmentBulkIngestRecord> request) {

        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        String actor = resolveActor(request);
        // A roster commonly lists the same person more than once; resolve each number once.
        Map<String, Optional<UUID>> personIds = new HashMap<>();

        for (int i = 0; i < request.getRecords().size(); i++) {
            StaffingAssignmentBulkIngestRecord record = request.getRecords().get(i);
            try {
                Optional<UUID> personId = personIds.computeIfAbsent(record.getEmployeeNumber(), this::resolvePerson);
                if (personId.isEmpty()) {
                    results.add(BulkIngestResult.builder()
                            .rowIndex(i)
                            .success(false)
                            .errorCode(UNKNOWN_EMPLOYEE)
                            .errorMessage("No employee with number " + record.getEmployeeNumber())
                            .build());
                    failureCount++;
                    continue;
                }

                StaffingAssignmentResponse created =
                        staffingAssignmentService.create(toRequest(record, request, personId.get()), actor);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getAssignmentId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest staffing assignment at row {}: {}", i, exception.getMessage());
                String message = exception.getMessage();
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(INGEST_FAILED)
                        .errorMessage(message == null || message.isBlank() ? "Assignment ingest failed" : message)
                        .build());
                failureCount++;
            }
        }

        return BulkIngestResponse.builder()
                .totalSubmitted(request.getRecords().size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(results)
                .build();
    }

    private Optional<UUID> resolvePerson(String employeeNumber) {
        return employeeService.resolveByEmployeeNumber(employeeNumber).map(identity -> identity.getPersonId());
    }

    private CreateStaffingAssignmentRequest toRequest(
            StaffingAssignmentBulkIngestRecord record,
            BulkIngestRequest<StaffingAssignmentBulkIngestRecord> request,
            UUID personId) {

        CreateStaffingAssignmentRequest assignment = new CreateStaffingAssignmentRequest();
        assignment.setPersonId(personId);
        assignment.setLocationId(record.getLocationId() == null ? request.getLocationId() : record.getLocationId());
        assignment.setRole(record.getRole());
        assignment.setPrimary(Boolean.TRUE.equals(record.getPrimary()));
        // Today, not a fixed date: a seeded roster is current as of the load, and a hard-coded date
        // would eventually be backdated far enough to overlap assignments nobody intended.
        assignment.setEffectiveFrom(
                record.getEffectiveFrom() == null ? LocalDate.now(clock) : record.getEffectiveFrom());
        assignment.setEffectiveTo(record.getEffectiveTo());
        return assignment;
    }

    private String resolveActor(BulkIngestRequest<StaffingAssignmentBulkIngestRecord> request) {
        return request.getOperatorId() == null || request.getOperatorId().isBlank()
                ? "system"
                : request.getOperatorId();
    }
}
