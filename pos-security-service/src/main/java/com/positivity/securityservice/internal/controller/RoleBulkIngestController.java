package com.positivity.securityservice.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.RoleBulkIngestRecord;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
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
import java.util.List;
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
 * Provisions roles in bulk, with their MCP persona metadata (#1613, D8).
 *
 * <p>Roles exist to bundle permission grants and to key an assistant persona. Neither is schema, and
 * treating role rows as Flyway migrations is what produced the drift this issue fixes: a role added
 * to SQL was invisible to anything that did not also get a Java edit.
 *
 * <p>Grants are not accepted here — they load separately, through
 * {@code RolePermissionBulkIngestController}, because permissions are registered code-first by each
 * module at startup and so are not all present when roles are created.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"security:role:create"})
@RequestMapping("/v1/roles")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Role Bulk Ingest API", description = "Bulk provision roles with persona metadata")
public class RoleBulkIngestController extends AbstractBulkIngestController<RoleBulkIngestRecord> {

    private static final String INGEST_FAILED = "ROLE_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"name":"SHOP_MANAGER","description":"Branch operations lead",
                "personaTitle":"shop manager","mcpPersonaRank":35},
               {"name":"WARRANTY_CLERK","description":"Warranty claim intake and settlement"}
             ]}
            """;

    private final RoleManagementService roleManagementService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_CREATE + "')")
    @EmitEvent(id = "SECURITY_ROLE_BULK_INGEST", apiVersion = "1")
    @Operation(operationId = "bulkIngestRoles", summary = "Provision Roles in Bulk", description = """
                    Creates roles from a bulk-load batch, each with an optional description and optional MCP persona \
                    metadata; every role starts with no permissions and no user assignments.
                    Use this tool to provision a set of roles from a load file; do not use it to grant permissions, \
                    which is bulkIngestRolePermissions, and do not use createRole, which creates one role at a time.
                    Preconditions: the caller must hold security:role:create.
                    Required inputs: jobId, locationId, and records; each record needs a non-blank name.
                    A role that already exists counts as a success — provisioning is expected to be re-runnable \
                    against an environment that is already partly seeded.
                    Persona slots must describe the role rather than instruct the assistant: single line, within the \
                    length cap, and free of imperative control verbs.
                    Emits a SECURITY_ROLE_BULK_INGEST event.
                    Returns 200 in all cases; inspect per-record success and failure in the response.
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
                            description = "Roles to provision, with optional persona metadata.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Roles", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<RoleBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<RoleBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            RoleBulkIngestRecord record = request.getRecords().get(i);
            try {
                RoleDto created = roleManagementService.createRole(new RoleCreateRequest(
                        record.name(),
                        record.description(),
                        record.personaTitle(),
                        record.personaFocus(),
                        record.personaTone(),
                        record.mcpPersonaRank(),
                        record.mcpPersonaEligible()));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (DuplicateRoleNameException exception) {
                // The role is already provisioned, which is the desired state. A baseline roles file
                // is applied repeatedly by design, so a re-run must not report failures.
                results.add(BulkIngestResult.builder().rowIndex(i).success(true).build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to provision role at row {}: {}", i, exception.getMessage());
                String message = exception.getMessage();
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(INGEST_FAILED)
                        .errorMessage(message == null || message.isBlank() ? "Role ingest failed" : message)
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
