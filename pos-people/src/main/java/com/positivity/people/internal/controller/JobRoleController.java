package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateJobRoleRequest;
import com.positivity.people.internal.dto.JobRoleDto;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.JobRoleService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling tenant's own job-role list (durion#2157): HR master data such as "Lead Technician"
 * or "Parts Counter" that {@code EmployeeProfileDto.jobRole} names an employee's job role from.
 * Never a permission-bearing role -- those live in pos-security-service.
 */
@RestController
@RequestMapping("/v1/people/job-roles")
@RequiredArgsConstructor
@Validated
@Tag(name = "Job Role API", description = "The tenant's own job-role list (HR master data)")
public class JobRoleController {

    private final JobRoleService jobRoleService;

    @GetMapping
    @EmitEvent(id = "PEOPLE_JOB_ROLE_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:jobRole:view"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.JOBROLE_VIEW + "')")
    @Operation(operationId = "listJobRoles", summary = "List The Tenant's Job Roles", description = """
                    Returns every active job role on the calling tenant's own list, ordered by name.
                    Use this tool to populate a job-role picker when creating or editing an employee, or to look up \
                    the id createJobRole needs; do not use it to look up application roles or permissions, which \
                    live in pos-security-service and are a different concept entirely.
                    Preconditions: the caller holds people:jobRole:view.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted beyond the audit read and no state changes; this is a read-only \
                    projection scoped to the caller's tenant.
                    Returns 200 with the list, empty when the tenant has defined none, and 403 without the \
                    authority.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The tenant's active job roles.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = JobRoleDto.class))))
    public ResponseEntity<List<JobRoleDto>> listJobRoles() {
        return ResponseEntity.ok(jobRoleService.listActive());
    }

    @PostMapping
    @EmitEvent(id = "PEOPLE_JOB_ROLE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:jobRole:manage"})
    @PreAuthorize("hasAuthority('" + PeoplePermissions.JOBROLE_MANAGE + "')")
    @Operation(operationId = "createJobRole", summary = "Add A Job Role To The Tenant's List", description = """
                    Adds a job role to the calling tenant's own list: a code, a display name and an optional \
                    description. HR master data only -- it carries no permissions and is never evaluated by \
                    security decisions.
                    Use this tool to set up the tenant's job titles before assigning them to employees; do not \
                    use it to create an application role or a permission, which are pos-security-service concepts.
                    Preconditions: the caller holds people:jobRole:manage; the code must not already be used by \
                    another job role in this tenant (another tenant may reuse the same code freely).
                    Required inputs: code and name; description is optional.
                    Emits a PEOPLE_JOB_ROLE_CREATE event; no other state changes.
                    Returns 422 when the code is already in use within this tenant.
                    """)
    @ApiResponse(responseCode = "201", description = "Job role created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Job role code already in use in this tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JobRoleDto> createJobRole(@Valid @RequestBody @NonNull CreateJobRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobRoleService.create(request));
    }
}
