package com.positivity.securityservice.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestFailures;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionBulkIngestRecord;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.RoleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Grants permissions to existing roles in bulk (#1613, D8 constraint 2).
 *
 * <p>A second load rather than grants carried on the role record, because permissions are registered
 * code-first by each module's {@code {Module}PermissionRegistry} at startup: the set a role can hold
 * is not knowable at the moment the role is created. Running this after the platform is up is also a
 * correctness gain over {@code R__seed_role_permissions.sql}, which ran during this service's own
 * boot — before the other modules had registered anything — and so was a snapshot that drifted from
 * the registries it was supposed to mirror.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"security:role:edit"})
@RequestMapping("/v1/roles/permissions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Role Permission Bulk Ingest API", description = "Bulk grant permissions to existing roles")
public class RolePermissionBulkIngestController extends AbstractBulkIngestController<RolePermissionBulkIngestRecord> {

    private static final String INGEST_FAILED = "ROLE_PERMISSION_INGEST_FAILED";

    /**
     * What {@link RoleManagementService#assignPermissionToRole} refuses about a grant: the role or
     * the permission the row names does not exist. Both are what
     * {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler} answers 404, so
     * both name something in the caller's own file — almost always a permission catalogue that has
     * not been registered yet, which is exactly what the operator needs told. Anything else is a
     * server-side fault and is reported as one (issue #1718).
     *
     * <p>This controller classifies per grant rather than per row, so it calls
     * {@link BulkIngestFailures} directly instead of overriding
     * {@code AbstractBulkIngestController#rowRejectionTypes}: a row here carries a role's whole
     * grant set, and one bad permission in it must not decide the whole row's verdict.
     */
    private static final List<Class<? extends Throwable>> ROW_REJECTION_TYPES =
            List.of(PermissionNotFoundException.class, RoleNotFoundException.class);

    private static final String UNKNOWN_ROLE = "ROLE_PERMISSION_ROLE_UNKNOWN";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"roleName":"SHOP_MANAGER","permissions":["crm:party:view","order:shipment:cancel"]},
               {"roleName":"TECHNICIAN","permissions":["workorder:job:update"]}
             ]}
            """;

    private final RoleManagementService roleManagementService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_EDIT + "')")
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestRolePermissions",
            summary = "Grant Permissions to Roles in Bulk",
            description = """
                    Grants named permissions to existing roles from a bulk-load batch.
                    Use this tool to apply a role-to-permission grant file; do not use it to create roles, which is \
                    bulkIngestRoles, and note that both the role and each permission must already exist — permissions \
                    are registered by their owning module at startup.
                    Preconditions: the caller must hold security:role:edit.
                    Required inputs: jobId, locationId, and records; each record needs a roleName and at least one \
                    permission name.
                    Re-granting a permission a role already holds is a success and does not rewrite who granted it \
                    originally, so a grant file is safe to re-apply.
                    Emits a SECURITY_ROLE_PERMISSION_BULK_INGEST event.
                    Returns 200 in all cases; a row naming an unknown role fails with ROLE_PERMISSION_ROLE_UNKNOWN \
                    while the rest of the batch continues. Grants are applied one at a time, so a row whose grants \
                    were refused carries ROLE_PERMISSION_INGEST_FAILED and names them, while a row where any grant \
                    was lost to a server-side fault carries INTERNAL_ERROR and a correlationId to quote instead.
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
                            description = "Role-to-permission grants to apply.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Grants", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<RolePermissionBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<RolePermissionBulkIngestRecord> request) {
        // One listing serves the whole batch. A per-row lookup would be a query per grant row, and a
        // grant file covers every role in the environment.
        Map<String, UUID> roleIdsByName = new HashMap<>();
        for (RoleDto role : roleManagementService.getAllRoles()) {
            roleIdsByName.put(role.getName().toUpperCase(Locale.ROOT), role.getId());
        }

        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            RolePermissionBulkIngestRecord record = request.getRecords().get(i);
            UUID roleId = roleIdsByName.get(record.roleName().toUpperCase(Locale.ROOT));
            if (roleId == null) {
                // Named rather than lumped into a generic failure: "the role does not exist" almost
                // always means the role load has not run yet, and the operator needs to know that.
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(UNKNOWN_ROLE)
                        .errorMessage("Role not found: " + record.roleName())
                        .build());
                failureCount++;
                continue;
            }

            // Per permission, not per row. A row here carries a role's whole grant set — several
            // hundred for ADMIN — each applied in its own transaction. Letting the first unresolvable
            // permission abort the loop would commit the grants before it, skip every grant after it,
            // and report one opaque failure; a re-run would stop at the same place. Collecting the
            // failures instead means the row applies everything it can and names exactly what it
            // could not.
            // Two outcomes are collected separately, because they are not the same news for the
            // operator: a permission this service refused (it does not exist, it is already
            // granted) is theirs to fix, while anything unclassifiable is ours. Reporting both as
            // "could not grant to X" told them the permission names were the problem even when a
            // database fault was (issue #1718 review).
            List<String> rejected = new ArrayList<>();
            List<String> serverFaults = new ArrayList<>();
            String faultCorrelationId = null;
            for (String permission : record.permissions()) {
                try {
                    roleManagementService.assignPermissionToRole(roleId, permission);
                } catch (Exception exception) {
                    if (BulkIngestFailures.isRowRejection(exception, ROW_REJECTION_TYPES)) {
                        log.warn(
                                "Refused permission {} for role {} at row {}: {}",
                                permission,
                                record.roleName(),
                                i,
                                exception.getMessage());
                        rejected.add(permission);
                    } else {
                        // The exception's own text never reaches the caller; the correlation id
                        // beside the ERROR entry is the whole handle, per ADR-0056. One id serves
                        // the row however many of its grants fail.
                        faultCorrelationId =
                                faultCorrelationId == null ? BulkIngestFailures.correlationId() : faultCorrelationId;
                        log.error(
                                "Failed to grant permission {} to role {} at row {} [correlationId={}]",
                                permission,
                                record.roleName(),
                                i,
                                faultCorrelationId,
                                exception);
                        serverFaults.add(permission);
                    }
                }
            }

            if (!serverFaults.isEmpty()) {
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(roleId)
                        .success(false)
                        .errorCode(BulkIngestFailures.INTERNAL_ERROR_CODE)
                        .errorMessage("Some grants could not be applied because of a server-side error")
                        .correlationId(faultCorrelationId)
                        .build());
                failureCount++;
            } else if (rejected.isEmpty()) {
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(roleId)
                        .success(true)
                        .build());
                successCount++;
            } else {
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(roleId)
                        .success(false)
                        .errorCode(INGEST_FAILED)
                        .errorMessage("Could not grant to " + record.roleName() + ": " + String.join(", ", rejected))
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
}
