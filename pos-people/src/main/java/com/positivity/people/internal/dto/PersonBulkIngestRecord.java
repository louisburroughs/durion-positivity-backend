package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Single person record within a bulk ingest payload")
public class PersonBulkIngestRecord {

    @NotBlank
    @Schema(
            description = "First (given) name of the person",
            example = "Jane",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotBlank
    @Schema(
            description = "Last (family) name of the person",
            example = "Smith",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @Schema(
            description = "Preferred name of the person",
            example = "Jane",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String preferredName;

    @NotBlank
    @Schema(description = "Unique employee number", example = "EMP-0001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String employeeNumber;

    @NotBlank
    @Schema(
            description = "Date the person was hired (ISO 8601 date)",
            example = "2026-01-15",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String hireDate;

    @Schema(
            description = "Primary email address",
            example = "jane.smith@example.com",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryEmail;

    @Schema(
            description = "Primary phone number",
            example = "+1-555-123-4567",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryPhone;
}
