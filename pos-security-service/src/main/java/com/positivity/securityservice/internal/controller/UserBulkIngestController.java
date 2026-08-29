package com.positivity.securityservice.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.UserBulkIngestRecord;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.exception.DuplicateUsernameException;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.UserService;
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
 * Provisions user accounts in bulk, with no password material anywhere in the request.
 *
 * <p>A bulk file is uploaded and stored, so a password column would exist at rest for as long as
 * the upload does. Each account is created with a password generated inside the service and
 * returned to no one; whoever is to use the account obtains one through the ordinary reset path.
 * That is a deliberate trade: bulk provisioning gets accounts and roles, not usable logins.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"security:user:create"})
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Bulk Ingest API", description = "Bulk provision user accounts")
public class UserBulkIngestController extends AbstractBulkIngestController<UserBulkIngestRecord> {

    private static final String INGEST_FAILED = "USER_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"username":"jane.doe","roles":["TECHNICIAN"]},
               {"username":"sam.lee","roles":["SHOP_MGR"]}
             ]}
            """;

    private final UserService userService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_CREATE + "')")
    @EmitEvent(id = "SECURITY_USER_BULK_INGEST", apiVersion = "1")
    @Operation(operationId = "bulkIngestUsers", summary = "Provision User Accounts in Bulk", description = """
                    Provisions many user accounts at once, each with its roles attached and a password generated \
                    inside the service.
                    Use this tool when standing up an environment's accounts; use createUser instead when the \
                    account needs a known password.
                    Preconditions: every role named must already exist.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a username and at \
                    least one role. There is deliberately no password field: a bulk file is stored, and a password \
                    in it would exist at rest for as long as the upload does.
                    Emits a SECURITY_USER_BULK_INGEST event and a user-created event per row; the generated \
                    passwords are returned to no one, so these accounts have no usable login until someone goes \
                    through the password reset path.
                    Re-running the same file is safe: an existing username is reported as already provisioned \
                    rather than as a failure.
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
                            description = "User accounts to provision. No password material is accepted.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Accounts", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<UserBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<UserBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            UserBulkIngestRecord record = request.getRecords().get(i);
            try {
                UserDto created = userService.createUserWithGeneratedPassword(record.username(), record.roles());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (DuplicateUsernameException exception) {
                // The account is already provisioned, which is the desired state.
                results.add(BulkIngestResult.builder().rowIndex(i).success(true).build());
                successCount++;
            } catch (Exception exception) {
                // Deliberately logs the username and nothing else: the row carries no secret, and
                // keeping it that way is the point of this endpoint.
                log.warn("Failed to provision user at row {}: {}", i, exception.getMessage());
                String message = exception.getMessage();
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(INGEST_FAILED)
                        .errorMessage(message == null || message.isBlank() ? "User ingest failed" : message)
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
