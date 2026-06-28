package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "A single customer record submitted as part of a bulk ingest request")
public class CustomerBulkIngestRecord {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "First name of the customer", example = "Jane", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @Schema(description = "Last name of the customer", example = "Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    // Bulk ingest tolerates dirty legacy data — length-bounded only, no strict
    // @Email format check, so a malformed value flags per-record rather than
    // 400-ing the whole batch.
    @Size(max = 254)
    @Schema(
            description = "Email address of the customer",
            example = "jane@acme.com",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String email;

    @Size(max = 64)
    @Schema(
            description = "Phone number of the customer",
            example = "+1-555-0142",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String phoneNumber;

    @Size(max = 255)
    @Schema(
            description = "Primary address of the customer",
            example = "123 Main St, Springfield",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryAddress;

    @Size(max = 50)
    @Schema(
            description = "Existing customer number, if known",
            example = "CUST-1001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String customerNumber;
}
