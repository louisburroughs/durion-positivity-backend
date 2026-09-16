package com.positivity.people.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CredentialUpsertCommand;
import com.positivity.people.internal.dto.PersonCredentialBulkIngestRecord;
import com.positivity.people.internal.dto.PersonCredentialResponse;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.people.internal.service.EmployeeService;
import com.positivity.people.internal.service.PersonCredentialService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk ingest of the credentials people hold (CAP-328). The feed that used to be shop-manager's
 * mechanic-skill enrichment lands here, in the module that owns mechanic data
 * (DECISION-SHOPMGMT-009), as credentials with issuers and dates.
 */
@RestController
@RequestMapping("/v1/people/credentials")
@RequiredArgsConstructor
@Tag(name = "Person Credential Bulk Ingest API", description = "Bulk import of the credentials people hold")
public class PersonCredentialBulkIngestController
        extends AbstractBulkIngestController<PersonCredentialBulkIngestRecord> {

    static final String UNKNOWN_EMPLOYEE = "CREDENTIAL_EMPLOYEE_UNKNOWN";
    static final String REJECTED = "CREDENTIAL_INGEST_REJECTED";
    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"employeeNumber":"EMP-0006","sourceCode":"ASE","sourceCredentialCode":"T4-BRAKES",
                "issuedOn":"2024-03-15","expiresOn":"2029-03-15","proficiency":4},
               {"employeeNumber":"EMP-0008","skillCode":"DOT-INSPECTOR","issuer":"SHOP",
                "issuedOn":"2025-01-10","expiresOn":"2027-01-10"}
             ]}
            """;

    private final PersonCredentialService personCredentialService;
    private final EmployeeService employeeService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_EDIT + "')")
    @EmitEvent(id = "PEOPLE_CREDENTIAL_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestPersonCredentials",
            summary = "Record People's Credentials in Bulk",
            description = """
                    Records many credentials at once, resolving each row's employee number to a person and its \
                    vendor code (sourceCode + sourceCredentialCode, such as ASE T4-BRAKES) to a registry skill \
                    through the cross-reference, or taking a Durion skillCode such as DOT-INSPECTOR directly.
                    Use this tool when loading an HR feed's certifications or seeding an environment; read the \
                    registry at GET /v1/people/skills instead of guessing codes.
                    Preconditions: each employee number must resolve to a person with an employee record, and \
                    each vendor code must be in the cross-reference — an unknown code rejects its row rather than \
                    becoming a skill nobody holds.
                    Required inputs: jobId, and per record employeeNumber, issuer, issuedOn and either skillCode \
                    or sourceCode + sourceCredentialCode; expiresOn, proficiency (1-5) and evidenceRef are optional, \
                    and expiresOn may not precede issuedOn.
                    Semantics: upsert by natural key (person, skill, issuer, issuedOn), so a re-send updates the \
                    expiry, proficiency and evidence, a renewal with a later issuedOn is a new row, and status is \
                    derived from the dates rather than taken from the row.
                    With supersedeAbsent=true, each person in the batch has their credentials from this job's \
                    sourceSystem that the batch no longer lists marked SUPERSEDED — never deleted.
                    Emits a PEOPLE_CREDENTIAL_BULK_INGEST event and a person-credential fact per row written.
                    Returns 200 with a per-record result, where a refused row carries CREDENTIAL_EMPLOYEE_UNKNOWN \
                    or CREDENTIAL_INGEST_REJECTED with the reason.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Batch processed; inspect per-record results",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BulkIngestResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Credentials to record.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "HR certifications",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<PersonCredentialBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    /**
     * Same as {@link #bulkIngest(BulkIngestRequest)} but with the supersession switch. Spring binds
     * the query parameter here; the base class's signature has no room for it.
     */
    @PostMapping(value = "/bulk-ingest", params = "supersedeAbsent")
    @PreAuthorize("hasAuthority('" + PeoplePermissions.EMPLOYEE_EDIT + "')")
    @EmitEvent(id = "PEOPLE_CREDENTIAL_BULK_INGEST", apiVersion = "1")
    @Operation(hidden = true)
    public ResponseEntity<BulkIngestResponse> bulkIngestSuperseding(
            @Valid @RequestBody @NonNull BulkIngestRequest<PersonCredentialBulkIngestRecord> request,
            @Parameter(description = "Mark this source's credentials the batch no longer lists SUPERSEDED")
                    @RequestParam(name = "supersedeAbsent", defaultValue = "false")
                    boolean supersedeAbsent) {
        return ResponseEntity.ok(process(request, supersedeAbsent));
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<PersonCredentialBulkIngestRecord> request) {
        return process(request, false);
    }

    private BulkIngestResponse process(
            BulkIngestRequest<PersonCredentialBulkIngestRecord> request, boolean supersedeAbsent) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        String actor = resolveActor(request);
        String sourceSystem = "bulk-ingest:" + request.getJobId();
        Map<String, Optional<UUID>> personIds = new HashMap<>();
        Map<UUID, Set<UUID>> writtenByPerson = new HashMap<>();
        for (int i = 0; i < request.getRecords().size(); i++) {
            PersonCredentialBulkIngestRecord record = request.getRecords().get(i);
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
                PersonCredentialResponse written =
                        personCredentialService.upsert(personId.get(), toCommand(record, sourceSystem), actor);
                writtenByPerson
                        .computeIfAbsent(personId.get(), id -> new HashSet<>())
                        .add(written.getCredentialId());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(written.getCredentialId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                results.add(rowFailure(i, exception));
                failureCount++;
            }
        }
        if (supersedeAbsent) {
            writtenByPerson.forEach((personId, retained) ->
                    personCredentialService.supersedeAbsent(personId, sourceSystem, retained, sourceSystem));
        }
        return BulkIngestResponse.builder()
                .totalSubmitted(request.getRecords().size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(results)
                .build();
    }

    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(SemanticValidationException.class, RequestValidationException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return REJECTED;
    }

    private Optional<UUID> resolvePerson(String employeeNumber) {
        return employeeService.resolveByEmployeeNumber(employeeNumber).map(identity -> identity.getPersonId());
    }

    private static CredentialUpsertCommand toCommand(PersonCredentialBulkIngestRecord record, String sourceSystem) {
        return CredentialUpsertCommand.builder()
                .skillCode(record.getSkillCode())
                .sourceCode(record.getSourceCode())
                .sourceCredentialCode(record.getSourceCredentialCode())
                .issuer(record.getIssuer())
                .issuedOn(record.getIssuedOn())
                .expiresOn(record.getExpiresOn())
                .proficiency(record.getProficiency())
                .evidenceRef(record.getEvidenceRef())
                .sourceSystem(sourceSystem)
                .build();
    }

    private static String resolveActor(BulkIngestRequest<PersonCredentialBulkIngestRecord> request) {
        return request.getOperatorId() == null || request.getOperatorId().isBlank()
                ? "bulk-ingest"
                : request.getOperatorId();
    }
}
