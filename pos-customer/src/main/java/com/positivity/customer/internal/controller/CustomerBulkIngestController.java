package com.positivity.customer.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.customer.internal.dto.CreatePersonRequest;
import com.positivity.customer.internal.dto.CustomerBulkIngestRecord;
import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.PersonService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collection;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {CrmPermissionRegistry.PARTY_CREATE})
@RequestMapping("/v1/customer")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
@Tag(name = "Customer Bulk Ingest API", description = "Bulk import customer records")
public class CustomerBulkIngestController extends AbstractBulkIngestController<CustomerBulkIngestRecord> {

    private final PersonService personService;

    @Override
    @io.swagger.v3.oas.annotations.Operation(
            operationId = "bulkIngestCustomers",
            summary = "Bulk Ingest Customer Records",
            description = """
                    Imports a batch of individual customer records, creating each one through the same path \
                    as createCrmPerson so canonical identities land in pos-people, and reports a per-row \
                    success or failure result without aborting the batch.
                    Use this tool for migrations and file imports of individuals; do not use createCrmPerson \
                    row by row for large loads, and note this path cannot create commercial accounts.
                    Preconditions: none beyond authorization; rows that fail validation are reported with \
                    errorCode CUSTOMER_INGEST_FAILED and the reason, and rows lost to a server-side fault with \
                    INGEST_INTERNAL_ERROR and an errorMessage holding only a correlationId to quote, while the \
                    rest of the batch proceeds.
                    Required inputs: jobId (UUID), locationId (UUID), and a non-empty records list where \
                    each record has firstName and lastName (each max 100) and optionally email, \
                    phoneNumber, primaryAddress, and customerNumber; preferredContactMethod is derived as \
                    EMAIL when an email is present and PHONE_CALL otherwise, and operatorId falls back to \
                    the security context when absent or not a UUID.
                    Emits a CUSTOMER_BULK_INGEST event, and each successful row publishes a party-changed \
                    customer fact and writes contact points to pos-people.
                    Returns 200 with per-row results including failures, and 400 when jobId, locationId, or \
                    the records list is missing or empty.
                    """)
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
    @EmitEvent(id = "CUSTOMER_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The ingest job envelope with the batch of customer records to import.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Two-record batch",
                                                            value = """
                                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a64",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a65",
                                                                     "operatorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a66",
                                                                     "records":[
                                                                       {"firstName":"Dana","lastName":"Ortiz","email":"dana.ortiz@example.com"},
                                                                       {"firstName":"Sam","lastName":"Rivera","phoneNumber":"+15125550143"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<CustomerBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<CustomerBulkIngestRecord> request) {
        UUID userId = null;
        if (StringUtils.hasText(request.getOperatorId())) {
            try {
                userId = UUID.fromString(request.getOperatorId());
            } catch (IllegalArgumentException _) {
                log.warn(
                        "operatorId '{}' is not a valid UUID; falling back to security context",
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
                    createPersonRequest.setEmails(List.of(CreatePersonRequest.EmailInput.builder()
                            .value(ingestRecord.getEmail())
                            .isPrimary(true)
                            .build()));
                }
                if (StringUtils.hasText(ingestRecord.getPhoneNumber())) {
                    createPersonRequest.setPhones(List.of(CreatePersonRequest.PhoneInput.builder()
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

    private PreferredContactMethod resolvePreferredContactMethod(@NonNull CustomerBulkIngestRecord ingestRecord) {
        return StringUtils.hasText(ingestRecord.getEmail())
                ? PreferredContactMethod.EMAIL
                : PreferredContactMethod.PHONE_CALL;
    }

    /**
     * The failures {@link com.positivity.customer.internal.service.PersonService#createPerson}
     * raises about the record itself. Its own guards throw {@code ResponseStatusException} with a
     * 400, which {@link com.positivity.bulkingest.BulkIngestFailures} already recognises as a
     * rejection, so only the module's CRM exception types need naming here — the ones
     * {@link com.positivity.customer.internal.config.CrmExceptionHandler} answers as a 4xx.
     * Everything else is a server-side fault and is reported generically against a correlation id
     * instead (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(
                CrmValidationException.class,
                CrmUnprocessableEntityException.class,
                CrmDuplicateResourceException.class,
                CrmResourceNotFoundException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "CUSTOMER_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Customer ingest failed";
    }
}
