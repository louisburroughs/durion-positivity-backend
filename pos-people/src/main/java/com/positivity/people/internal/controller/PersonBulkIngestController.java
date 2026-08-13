package com.positivity.people.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.PersonBulkIngestRecord;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"people:employee:create"})
@RequestMapping("/v1/people")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('people:employee:create')")
@Tag(name = "People Bulk Ingest API", description = "Bulk import employee records")
public class PersonBulkIngestController extends AbstractBulkIngestController<PersonBulkIngestRecord> {

    private final EmployeeService employeeService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('people:employee:create')")
    @EmitEvent(id = "PEOPLE_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "createEmployeesBulk",
            summary = "Bulk Import Employee Records From Batch",
            description = """
                    Imports a batch of employee records, creating each one through the same path as createEmployee \
                    with status forced to ACTIVE.
                    Use this tool for initial loads or migrations of many employees; use createEmployee instead for \
                    a single hire, since per-record failures here are reported in the response body rather than as \
                    an HTTP error.
                    Preconditions: each record is checked under the STRICT duplicate policy, so an existing employee \
                    number, email, or phone fails that record.
                    Required inputs: jobId (UUID), locationId (UUID), and records, each with firstName, lastName, \
                    employeeNumber, and hireDate as a YYYY-MM-DD string.
                    Emits a PEOPLE_BULK_INGEST event, and each successfully created employee also publishes its \
                    identity upsert command and people.employee.updated fact.
                    Returns 200 even when individual records fail (inspect per-record success flags and errorCode \
                    PEOPLE_INGEST_FAILED), and 400 when the envelope itself is invalid or the records list is empty.
                    """)
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Bulk ingest envelope: a job-scoped batch of person records to import as"
                                    + " ACTIVE employees.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two-person batch", value = """
                                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "operatorId":"user-jdoe",
                                                                     "records":[
                                                                       {"firstName":"Jane","lastName":"Smith",
                                                                        "employeeNumber":"EMP-0001","hireDate":"2026-01-15",
                                                                        "primaryEmail":"jane.smith@example.com"},
                                                                       {"firstName":"John","lastName":"Doe",
                                                                        "employeeNumber":"EMP-0002","hireDate":"2026-02-01",
                                                                        "primaryPhone":"+1-555-123-4567"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<PersonBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<PersonBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            PersonBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                LocalDate hireDate = parseHireDate(ingestRecord.getHireDate());

                CreateEmployeeRequest createEmployeeRequest = new CreateEmployeeRequest();
                createEmployeeRequest.setFirstName(ingestRecord.getFirstName());
                createEmployeeRequest.setLastName(ingestRecord.getLastName());
                createEmployeeRequest.setPreferredName(ingestRecord.getPreferredName());
                createEmployeeRequest.setEmployeeNumber(ingestRecord.getEmployeeNumber());
                createEmployeeRequest.setStatus(EmployeeStatus.ACTIVE);
                createEmployeeRequest.setHireDate(hireDate);

                if (StringUtils.hasText(ingestRecord.getPrimaryEmail())
                        || StringUtils.hasText(ingestRecord.getPrimaryPhone())) {
                    EmployeeContactInfoDto contactInfo = new EmployeeContactInfoDto();
                    contactInfo.setPrimaryEmail(ingestRecord.getPrimaryEmail());
                    contactInfo.setPrimaryPhone(ingestRecord.getPrimaryPhone());
                    createEmployeeRequest.setContactInfo(contactInfo);
                }

                var created = employeeService.createEmployee(createEmployeeRequest);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest people record at row {}: {}", i, exception.getMessage(), exception);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode("PEOPLE_INGEST_FAILED")
                        .errorMessage(errorMessage(exception))
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

    private String errorMessage(@NonNull Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "People ingest failed" : message;
    }

    private LocalDate parseHireDate(String hireDateValue) {
        try {
            return LocalDate.parse(hireDateValue);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid hireDate format; expected YYYY-MM-DD", exception);
        }
    }
}
