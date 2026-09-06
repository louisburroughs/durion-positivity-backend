package com.positivity.securityservice.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserPersonLinkBulkIngestRecord;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Links user accounts to their canonical persons in bulk.
 *
 * <p>Each row names its account rather than identifying it, and the username is resolved here —
 * usernames are this service's own key, so a caller should not have to look one up per row.
 *
 * <p>Linking is asynchronous: this queues the command and the {@code users.person_id} projection
 * lands when the confirming fact returns. A row reported as successful therefore means the link was
 * accepted for processing, not that it has been applied — read the accounts back afterwards to
 * confirm.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"security:user:edit"})
@RequestMapping("/v1/users/person-link")
@RequiredArgsConstructor
@Tag(name = "User Person Link Bulk Ingest API", description = "Bulk link user accounts to persons")
public class UserPersonLinkBulkIngestController extends AbstractBulkIngestController<UserPersonLinkBulkIngestRecord> {

    private static final String INGEST_FAILED = "USER_PERSON_LINK_INGEST_FAILED";
    private static final String UNKNOWN_USER = "USER_PERSON_LINK_USER_UNKNOWN";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"username":"jane.doe","personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
             ]}
            """;

    private final UserService userService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_EDIT + "')")
    @EmitEvent(id = "SECURITY_USER_PERSON_LINK_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestUserPersonLinks",
            summary = "Link User Accounts to Persons in Bulk",
            description = """
                    Queues many user-to-person links at once, resolving each row's username to an account.
                    Use this tool when connecting seeded accounts to their staff records; use linkUserPerson \
                    instead for a single account.
                    Preconditions: each username must name an existing account. The person id is not validated \
                    here — pos-people-contact rejects an unknown person when it processes the command, which \
                    surfaces later rather than as a row failure.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a username and a \
                    personId.
                    Emits a SECURITY_USER_PERSON_LINK_BULK_INGEST event and queues a link command per row; \
                    linking is asynchronous, so a successful row means the link was accepted rather than applied \
                    and the accounts should be read back to confirm the projection landed.
                    Re-running the same file is safe: the consumer upserts by username.
                    Returns 200 with a per-record result; check each result's errorCode rather than the status \
                    alone, which is USER_PERSON_LINK_INGEST_FAILED with the reason for a row the service refused, or \
                    INTERNAL_ERROR with a correlationId to quote for a row lost to a server-side fault.
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
                            description = "User-to-person links to queue.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Links", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<UserPersonLinkBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<UserPersonLinkBulkIngestRecord> request) {

        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        // One listing serves the whole batch; a per-row lookup would be a query per link.
        Map<String, UUID> userIdsByUsername = new HashMap<>();
        for (UserDto user : userService.getAllUsers()) {
            userIdsByUsername.put(user.getUsername(), user.getId());
        }

        for (int i = 0; i < request.getRecords().size(); i++) {
            UserPersonLinkBulkIngestRecord record = request.getRecords().get(i);
            UUID userId = userIdsByUsername.get(record.username());
            if (userId == null) {
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(UNKNOWN_USER)
                        .errorMessage("No user account named " + record.username())
                        .build());
                failureCount++;
                continue;
            }
            try {
                userService.requestPersonLink(userId, record.personId());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(userId)
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                results.add(rowFailure(i, exception));
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

    /**
     * What linking refuses about the record itself: a user the row names that does not exist, and
     * plain field validation. Both are what
     * {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler} answers as a
     * 4xx. Everything else is a server-side fault, reported generically against a correlation id
     * (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(UserNotFoundException.class, SecurityValidationException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return INGEST_FAILED;
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Person link ingest failed";
    }
}
