package com.positivity.customer.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CustomerBulkIngestRecord;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.customer.service.PersonService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
    CrmPermissionRegistry.PARTY_CREATE })
@RequestMapping("/v1/customer")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
@Tag(name = "Customer Bulk Ingest API", description = "Bulk import customer records")
public class CustomerBulkIngestController extends AbstractBulkIngestController<CustomerBulkIngestRecord> {

  private final PersonService personService;

  @Override
  @PostMapping("/bulk-ingest")
  @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
  @EmitEvent(id = "CUSTOMER_BULK_INGEST", apiVersion = "1")
  public ResponseEntity<BulkIngestResponse> bulkIngest(
      @Valid @RequestBody @NonNull BulkIngestRequest<CustomerBulkIngestRecord> request) {
    return super.bulkIngest(request);
  }

  @Override
  protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<CustomerBulkIngestRecord> request) {
    UUID userId = null;
    if (StringUtils.hasText(request.getOperatorId())) {
      try {
        userId = UUID.fromString(request.getOperatorId());
      } catch (IllegalArgumentException _) {
        log.warn("operatorId '{}' is not a valid UUID; falling back to security context",
            request.getOperatorId());
      }
    }
    if (userId == null) {
      userId = SecurityContextHelper.getCurrentUserIdAsUuid().orElse(null);
    }

    List<BulkIngestResult> results = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;

    for (int i = 0; i < request.getRecords().size(); i++) {
      CustomerBulkIngestRecord ingestRecord = request.getRecords().get(i);
      try {
        CreatePersonRequest createPersonRequest = new CreatePersonRequest();
        createPersonRequest.setFirstName(ingestRecord.getFirstName());
        createPersonRequest.setLastName(ingestRecord.getLastName());
        createPersonRequest.setPreferredContactMethod(resolvePreferredContactMethod(ingestRecord));
        if (StringUtils.hasText(ingestRecord.getEmail())) {
          createPersonRequest.setEmails(List.of(
              CreatePersonRequest.EmailInput.builder()
                  .value(ingestRecord.getEmail())
                  .isPrimary(true)
                  .build()));
        }
        if (StringUtils.hasText(ingestRecord.getPhoneNumber())) {
          createPersonRequest.setPhones(List.of(
              CreatePersonRequest.PhoneInput.builder()
                  .value(ingestRecord.getPhoneNumber())
                  .type(ContactPointType.PHONE_MOBILE)
                  .isPrimary(true)
                  .build()));
        }

        var created = personService.createPerson(createPersonRequest, userId);
        results.add(BulkIngestResult.builder()
            .rowIndex(i)
            .entityId(created.getPersonId())
            .success(true)
            .build());
        successCount++;
      } catch (Exception exception) {
        log.warn("Failed to ingest customer record at row {}: {}", i, exception.getMessage(), exception);
        results.add(BulkIngestResult.builder()
            .rowIndex(i)
            .success(false)
            .errorCode("CUSTOMER_INGEST_FAILED")
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

  private PreferredContactMethod resolvePreferredContactMethod(@NonNull CustomerBulkIngestRecord ingestRecord) {
    return StringUtils.hasText(ingestRecord.getEmail())
        ? PreferredContactMethod.EMAIL
        : PreferredContactMethod.PHONE_CALL;
  }

  private String errorMessage(@NonNull Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? "Customer ingest failed" : message;
  }
}