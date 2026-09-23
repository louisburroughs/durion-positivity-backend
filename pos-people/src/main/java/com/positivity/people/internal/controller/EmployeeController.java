package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeStatusCountsResponse;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.EnableEmployeeRequestDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.enums.EmployeeSearchInclude;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.EmployeeService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/people/employees")
@RequiredArgsConstructor
@Validated
@Tag(name = "Employee API", description = "Employee profile and offboarding operations")
public class EmployeeController {

    private final EmployeeService employeeService;

    @PostMapping
    @EmitEvent(id = "PEOPLE_EMPLOYEE_CREATE", apiVersion = "1")
    @Operation(operationId = "createEmployee", summary = "Create A New Employee Profile", description = """
                    Creates an employee profile: employment attributes (employee number, status, hire date) are stored \
                    in the people domain, while identity attributes (names, contact info) are forwarded to \
                    pos-people-contact as an upsert command with a server-generated UUIDv7 person id.
                    Use this tool when hiring or registering a brand-new employee; do not use updateEmployee, which \
                    modifies an existing profile, and do not use createEmployeesBulk, which imports many records in \
                    one call.
                    Preconditions: no existing employee may match the employee number, primary email, or phone when \
                    duplicatePolicy is STRICT (the default); identity duplicate checks run against an eventually \
                    consistent replica.
                    Required inputs: firstName, lastName, employeeNumber, status (ACTIVE, ON_LEAVE, SUSPENDED, \
                    TERMINATED, DISABLED) and hireDate (yyyy-MM-dd); duplicatePolicy defaults to STRICT, and BALANCED \
                    accepts suspected duplicates while returning warnings in the response.
                    Emits a PEOPLE_EMPLOYEE_CREATE event, publishes a people.employee.updated fact, and sends a person \
                    upsert command to pos-people-contact; the response echoes the submitted identity fields because \
                    the replica may lag.
                    Returns 409 when a duplicate employee number, email, or phone is detected under STRICT policy, \
                    and 422 when terminationDate is before hireDate.
                    """)
    @ApiResponse(responseCode = "201", description = "Employee created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Duplicate employee",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Semantic validation failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:create"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_CREATE + "')")
    public ResponseEntity<EmployeeProfileDto> createEmployee(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Employee profile to create: identity fields forwarded to pos-people-contact plus"
                                            + " local employment attributes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "New technician", value = """
                                                                    {"firstName":"Jane","lastName":"Smith",
                                                                     "preferredName":"Jane",
                                                                     "employeeNumber":"EMP-0001",
                                                                     "status":"ACTIVE","hireDate":"2026-01-15",
                                                                     "contactInfo":{"primaryEmail":"jane.smith@example.com",
                                                                       "primaryPhone":"+1-555-123-4567"},
                                                                     "duplicatePolicy":"STRICT"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    CreateEmployeeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.createEmployee(request));
    }

    @GetMapping
    @EmitEvent(id = "PEOPLE_EMPLOYEE_SEARCH", apiVersion = "1")
    @Operation(operationId = "searchEmployees", summary = "Search Employees By Name Or Number", description = """
                    Returns a paged list of slim employee rows matching a case-insensitive substring search across \
                    first name, last name, preferred name, and employee number, optionally narrowed to one or more \
                    employment statuses and sorted.
                    Use this tool when listing, filtering, sorting, or typeahead-filtering employees; do not use \
                    getEmployee, which requires the person id already be known, do not use getEmployeeByNumber, \
                    which resolves one exact employee number rather than searching, and do not use this tool to \
                    render the register's stat tiles -- use getEmployeeStatusCounts instead, which returns the \
                    status histogram over the same q filter without changing this endpoint's response shape.
                    Preconditions: none; an empty result set is returned rather than an error when nothing matches.
                    Required inputs: none are mandatory; q defaults to blank, which lists every employee; status \
                    defaults to none, which applies no status filter; sort defaults to lastName,asc; page \
                    defaults to 0, and size defaults to 20 with a maximum of 100. The status filter and sort are \
                    applied across every matching employee, not just the returned page, so totalElements and \
                    ordering are both correct for a result set spanning more than one page.
                    Emits a PEOPLE_EMPLOYEE_SEARCH audit event but changes no state; this is a read-only projection \
                    merged in memory from local employment rows and the pos-people-contact identity replica.
                    Register enrichment (durion#2155, plus ALLOWED_ACTIONS from durion#2159): include= repeatable \
                    tokens (USERNAME, CONTACT_INFO, ROLE_ASSIGNMENTS, LOCATION, JOB_ROLE, ALLOWED_ACTIONS) each \
                    turn on one extra field/group on the returned rows -- username, contact info, active \
                    application roles, a single primary location plus a count of the rest, job role, and the \
                    caller's allowed actions on the row -- so the register can render a full page in this one call \
                    instead of one follow-up call per row per column. Omitted, every one of those fields is null: \
                    the pre-#2155 thin row, byte for byte, so an existing caller (e.g. HrFacadeTool.searchEmployees) \
                    sees no change. Whichever categories are requested are resolved against the page actually \
                    returned, never the whole matching set, so the response cost stays flat as the tenant grows. \
                    CONTACT_INFO carries a second, narrower gate: email/phone appear only when the caller also \
                    holds people:employee_pii:view (#1898) -- requesting it without that permission still \
                    returns 200, just with the field absent from every row, never a 403. ALLOWED_ACTIONS is a \
                    RENDERING HINT ONLY -- computed by EmployeeActionPolicy from the caller's permissions and each \
                    row's status, never a substitute for the @PreAuthorize and service-level guards those actions \
                    still enforce independently -- and does not account for location-scoped access enforced \
                    elsewhere in this module, so a location-scoped caller may occasionally see an action listed \
                    that their scope does not actually cover for that employee.
                    Returns 200 with an empty items list and correct totals when the page, query, or status \
                    filter matches nothing.
                    """)
    @ApiResponse(responseCode = "200", description = "Employees returned (possibly empty)")
    @ApiResponse(
            responseCode = "400",
            description = "Unsupported sort field or direction",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_VIEW + "')")
    public ResponseEntity<PagedResponse<EmployeeSummaryDto>> searchEmployees(
            @Parameter(description = "Case-insensitive substring match on name or employee number; blank lists all")
                    @RequestParam(required = false)
                    String q,
            @Parameter(
                            description = "Employment status filter; repeatable (?status=ACTIVE&status=DISABLED). "
                                    + "Omitted or empty applies no status filter and lists every status.")
                    @RequestParam(required = false)
                    List<EmployeeStatus> status,
            @Parameter(
                            description = "Sort field with optional direction, e.g. 'lastName,desc'. Only "
                                    + "lastName is supported today. Direction defaults to asc.")
                    @RequestParam(required = false, defaultValue = "lastName,asc")
                    String sort,
            @Parameter(description = "Zero-based page index") @PositiveOrZero @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(description = "Page size, up to 100") @Positive @Max(100) @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(
                            description = "Register-enrichment categories (durion#2155, plus ALLOWED_ACTIONS from "
                                    + "durion#2159); repeatable "
                                    + "(?include=USERNAME&include=ROLE_ASSIGNMENTS), matching how `status` "
                                    + "above is passed. Omitted or empty returns the thin pre-#2155 row: "
                                    + "username, contactInfo, roleAssignments, primaryLocation, "
                                    + "otherLocationCount, jobRole and allowedActions are all null. "
                                    + "CONTACT_INFO additionally requires people:employee_pii:view (#1898); "
                                    + "without it the field is simply absent, never a 403. ALLOWED_ACTIONS "
                                    + "is a rendering hint only -- see EmployeeSummaryDto.allowedActions.")
                    @RequestParam(required = false)
                    List<EmployeeSearchInclude> include) {
        return ResponseEntity.ok(employeeService.searchEmployees(q, status, sort, page, size, include));
    }

    @GetMapping("/status-counts")
    @EmitEvent(id = "PEOPLE_EMPLOYEE_STATUS_COUNTS", apiVersion = "1")
    @Operation(
            operationId = "getEmployeeStatusCounts",
            summary = "Get Employee Status Histogram For The Register",
            description = """
                    Returns a per-status employee count for the employee register's stat tiles, computed over the \
                    same case-insensitive name/employee-number q filter searchEmployees applies, before any status \
                    filter -- so every tile reports what selecting that status would return out of the current \
                    search, including for a status not currently selected.
                    Use this tool alongside searchEmployees to render the register's stat-tile row; do not use it \
                    in place of searchEmployees, which alone returns the paged row list (durion#2158: this \
                    histogram used to be folded into that endpoint's response, which changed its shape for every \
                    caller -- it is now this separate, additive endpoint instead).
                    Preconditions: none; an empty tenant, or a q that matches nothing, returns an empty counts map \
                    rather than an error.
                    Required inputs: none are mandatory; q defaults to blank, which counts every employee. Unlike \
                    searchEmployees this endpoint takes no status, sort, page, size, or include parameters -- the \
                    histogram always covers the whole q-filtered set, never one page of it.
                    Emits a PEOPLE_EMPLOYEE_STATUS_COUNTS audit event but changes no state; this is a read-only \
                    projection merged in memory the same way searchEmployees is, and carries a bucket for an \
                    employee with no status recorded (a legacy row) so the counts always sum to the q-filtered \
                    total.
                    Returns 200 with an empty counts map when q matches nothing.
                    """)
    @ApiResponse(responseCode = "200", description = "Status histogram returned (possibly empty)")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_VIEW + "')")
    public ResponseEntity<EmployeeStatusCountsResponse> getEmployeeStatusCounts(
            @Parameter(description = "Case-insensitive substring match on name or employee number; blank counts all")
                    @RequestParam(required = false)
                    String q) {
        return ResponseEntity.ok(employeeService.employeeStatusCounts(q));
    }

    @PutMapping("/{employeeId}")
    @EmitEvent(id = "PEOPLE_EMPLOYEE_UPDATE", apiVersion = "1")
    @Operation(operationId = "updateEmployee", summary = "Update An Existing Employee Profile", description = """
                    Updates an existing employee profile by person id, replacing employment attributes locally and \
                    forwarding identity attributes to pos-people-contact as an upsert command.
                    Use this tool when correcting or changing an existing employee's details; do not use \
                    createEmployee, which registers a new person, and do not use disableEmployee, which is the \
                    offboarding transition.
                    Preconditions: an employee row or identity-replica row must exist for the supplied employeeId, \
                    which is the person id at this API surface.
                    Required inputs: employeeId (UUID) path parameter plus the full profile (firstName, lastName, \
                    employeeNumber, status, hireDate); this is a full replacement, not a patch, and duplicatePolicy \
                    defaults to STRICT.
                    Emits a PEOPLE_EMPLOYEE_UPDATE event and publishes a people.employee.updated fact; a status \
                    change also stamps statusEffectiveAt.
                    Returns 404 when no employee or person exists for the id, 409 when another employee already uses \
                    the employee number, email, or phone under STRICT policy, and 422 when terminationDate is before \
                    hireDate.
                    """)
    @ApiResponse(responseCode = "200", description = "Employee updated")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Employee not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Duplicate employee",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Semantic validation failure",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_EDIT + "')")
    public ResponseEntity<EmployeeProfileDto> updateEmployee(
            @PathVariable UUID employeeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement employee profile; identity fields are re-sent to"
                                    + " pos-people-contact as an upsert command.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Status change to leave", value = """
                                                                    {"firstName":"Jane","lastName":"Smith",
                                                                     "employeeNumber":"EMP-0001",
                                                                     "status":"ON_LEAVE","hireDate":"2026-01-15",
                                                                     "duplicatePolicy":"BALANCED"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    UpdateEmployeeRequest request) {
        return ResponseEntity.ok(employeeService.updateEmployee(employeeId, request));
    }

    @GetMapping("/{employeeId}")
    @EmitEvent(id = "PEOPLE_EMPLOYEE_GET", apiVersion = "1")
    @Operation(operationId = "getEmployee", summary = "Get Employee Profile By Person Id", description = """
                    Returns the full employee profile for a person id, merging identity fields from the \
                    pos-people-contact replica with local employment fields, including the contactInfo block: \
                    personal address, personal phone numbers, personal email and emergency contact.
                    Use this tool when the person id is already known; use getEmployeeByNumber instead to resolve a \
                    human-entered employee number, and searchEmployees to list or pick an employee without their \
                    personal contact detail.
                    Preconditions: the caller holds people:employee_pii:view, which is narrower than the \
                    people:employee:view held by the structural reads; an employee row or identity-replica row must \
                    exist for the id; identity fields may briefly be null right after creation while the replica \
                    catches up.
                    Required inputs: employeeId (UUID) path parameter, which is the person id; there is no request \
                    body.
                    Emits a PEOPLE_EMPLOYEE_GET audit event but changes no state; this is a read-only projection.
                    Returns 403 when the caller does not hold people:employee_pii:view, and 404 when neither an \
                    employee record nor a person replica row exists for the id.
                    """)
    @ApiResponse(responseCode = "200", description = "Employee found")
    @ApiResponse(
            responseCode = "404",
            description = "Employee not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee_pii:view"})
    // #1898: this is the only read that returns EmployeeProfileDto.contactInfo — home address,
    // personal phone and emergency contact — so it is gated on the narrow PII permission rather
    // than the people:employee:view every staff role holds. The structural reads on this
    // controller keep people:employee:view.
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_PII_VIEW + "')")
    public ResponseEntity<EmployeeProfileDto> getEmployee(@PathVariable UUID employeeId) {
        return ResponseEntity.ok(employeeService.getEmployee(employeeId));
    }

    @GetMapping("/by-number/{employeeNumber}")
    @Operation(operationId = "getEmployeeByNumber", summary = "Resolve Employee By Employee Number", description = """
                    Resolves an employee number to a slim identity projection containing the person id, employee \
                    number, employment status, and an active flag.
                    Use this tool for service-to-service approver resolution such as \
                    manager-approval-by-employee-number; use getEmployee instead when the full profile with names and \
                    contact info is needed, which requires the narrower people:employee_pii:view.
                    Preconditions: an employee record with the given employee number must exist; matching is \
                    case-insensitive.
                    Required inputs: employeeNumber (string) path parameter; there is no request body and no \
                    pagination.
                    No events are emitted and no state changes; this is a read-only lookup.
                    Returns 404 when no employee carries the supplied employee number.
                    """)
    @ApiResponse(responseCode = "200", description = "Employee resolved")
    @ApiResponse(
            responseCode = "404",
            description = "No employee with that number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_VIEW + "')")
    public ResponseEntity<EmployeeIdentityDto> resolveByNumber(@PathVariable @NonNull String employeeNumber) {
        return employeeService
                .resolveByEmployeeNumber(employeeNumber)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Employee not found for employee number"));
    }

    @PostMapping("/{employeeId}/disable")
    @EmitEvent(id = "PEOPLE_EMPLOYEE_DISABLE", apiVersion = "1")
    @Operation(
            operationId = "disableEmployee",
            summary = "Disable Employee And Offboard Assignments",
            description = """
                    Disables an ACTIVE employee, setting status DISABLED with a fresh statusEffectiveAt, and applies \
                    the requested staffing-assignment offboarding policy.
                    Use this tool for offboarding; do not use updateEmployee to force the status field, which skips \
                    offboarding, and do not use endStaffingAssignment, which ends a single assignment only.
                    Preconditions: the employee must exist and be in ACTIVE status; ON_LEAVE or SUSPENDED employees \
                    are rejected, as are already DISABLED or TERMINATED ones.
                    Required inputs: employeeId (UUID) path parameter; the body is optional, assignmentPolicy \
                    defaults to IMMEDIATE, and assignmentEndDate applies only with GRACE_PERIOD.
                    Emits a PEOPLE_EMPLOYEE_DISABLE event and publishes a people.employee.updated fact; when the \
                    downstream assignment action fails, a retry is queued with a five-minute delay instead of \
                    failing the request.
                    Returns 404 when the employee does not exist, and 409 when the employee is not currently ACTIVE \
                    (an already DISABLED, TERMINATED, ON_LEAVE, or SUSPENDED employee cannot be disabled again).
                    """)
    @ApiResponse(responseCode = "200", description = "Employee disabled")
    @ApiResponse(
            responseCode = "404",
            description = "Employee not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Employee is not currently ACTIVE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:activation"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_ACTIVATION + "')")
    public ResponseEntity<EmployeeProfileDto> disableEmployee(
            @PathVariable UUID employeeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional offboarding metadata controlling how the employee's staffing"
                                    + " assignments are terminated; omitting it applies the IMMEDIATE policy.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Grace-period offboarding", value = """
                                                                    {"disableReason":"Voluntary resignation",
                                                                     "assignmentPolicy":"GRACE_PERIOD",
                                                                     "assignmentEndDate":"2026-03-31"}
                                                                    """)))
                    @RequestBody(required = false)
                    DisableEmployeeRequestDto request) {
        DisableEmployeeRequestDto resolved = request != null ? request : new DisableEmployeeRequestDto();
        return ResponseEntity.ok(employeeService.disableEmployee(employeeId, resolved));
    }

    @PostMapping("/{employeeId}/enable")
    @EmitEvent(id = "PEOPLE_EMPLOYEE_ENABLE", apiVersion = "1")
    @Operation(operationId = "enableEmployee", summary = "Enable A Disabled Employee", description = """
                    Reactivates a DISABLED employee, setting status ACTIVE with a fresh statusEffectiveAt. This is \
                    the explicit DISABLED -> ACTIVE transition DECISION-PEOPLE-001 calls for, and the direct \
                    inverse of disableEmployee: staffing assignments are left exactly as disableEmployee's \
                    offboarding policy left them, never silently resurrected.
                    Use this tool to bring a DISABLED employee back to ACTIVE; do not use updateEmployee to force \
                    the status field, which is gated on the broader profile-edit permission rather than this \
                    activation permission and runs no confirmation semantics, and do not use this tool for \
                    ON_LEAVE or SUSPENDED employees, which carry dates and a reason that only updateEmployee \
                    collects.
                    Preconditions: the employee must exist and currently be DISABLED; TERMINATED is rejected as \
                    irreversible, ACTIVE is rejected as already active, and ON_LEAVE or SUSPENDED are rejected in \
                    favor of updateEmployee.
                    Required inputs: employeeId (UUID) path parameter; the request body is required and carries \
                    updatedAt, the concurrency token also returned as EmployeeProfileDto.updatedAt — submit back \
                    the value most recently read for this employee so a change made in the meantime is caught \
                    rather than silently overwritten.
                    Emits a PEOPLE_EMPLOYEE_ENABLE event and publishes a people.employee.updated fact, so the \
                    downstream replicas disableEmployee notified converge back to ACTIVE without a manual replay.
                    Returns 404 when the employee does not exist, and 409 when the employee is TERMINATED \
                    (irreversible), ON_LEAVE or SUSPENDED (use updateEmployee instead), already ACTIVE, or when \
                    the submitted updatedAt no longer matches the employee's current value.
                    """)
    @ApiResponse(responseCode = "200", description = "Employee enabled")
    @ApiResponse(
            responseCode = "404",
            description = "Employee not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Employee is not currently DISABLED, or the concurrency token is stale",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:activation"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_ACTIVATION + "')")
    public ResponseEntity<EmployeeProfileDto> enableEmployee(
            @PathVariable UUID employeeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Concurrency token guarding the reactivation: the updatedAt value most"
                                    + " recently read for this employee.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reactivate with last-known updatedAt",
                                                            value = """
                                                                    {"updatedAt":"2026-02-01T14:05:00Z"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    EnableEmployeeRequestDto request) {
        return ResponseEntity.ok(employeeService.enableEmployee(employeeId, request));
    }
}
