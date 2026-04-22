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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/people")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "People Bulk Ingest API", description = "Bulk import employee records")
public class PersonBulkIngestController extends AbstractBulkIngestController<PersonBulkIngestRecord> {

  private final EmployeeService employeeService;

  @Override
  @PreAuthorize("hasAuthority('people:employee:create')")
  @EmitEvent(id = "PEOPLE_BULK_INGEST", apiVersion = "1")
  public ResponseEntity<BulkIngestResponse> bulkIngest(
      @Valid @RequestBody @NonNull BulkIngestRequest<PersonBulkIngestRecord> request) {
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
        LocalDate hireDate;
        try {
          hireDate = LocalDate.parse(ingestRecord.getHireDate());
        } catch (DateTimeParseException exception) {
          throw new IllegalArgumentException(
              "Invalid hireDate format; expected YYYY-MM-DD", exception);
        }

        CreateEmployeeRequest createEmployeeRequest = new CreateEmployeeRequest();
        createEmployeeRequest.setLegalName(ingestRecord.getLegalName());
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
}