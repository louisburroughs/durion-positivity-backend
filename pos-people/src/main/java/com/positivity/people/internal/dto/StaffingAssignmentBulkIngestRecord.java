package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

/** A single staffing assignment within a bulk ingest request. */
@Data
@Schema(description = "One person's assignment to a location for a role")
public class StaffingAssignmentBulkIngestRecord {

    @Schema(
            description = "Employee number of the person being assigned. Resolved to a person id here, so a file"
                    + " can be written without knowing ids the pipeline generates.",
            example = "EMP-0001",
            requiredMode = REQUIRED)
    @NotBlank
    private String employeeNumber;

    @Schema(
            description = "Location the person is assigned to. Defaults to the request's locationId when omitted.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Role at that location", example = "TECHNICIAN", requiredMode = REQUIRED)
    @NotBlank
    private String role;

    @Schema(
            description = "Whether this is the person's primary assignment",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean primary;

    @Schema(
            description = "First day of the assignment; defaults to today when omitted, since a seeded roster"
                    + " is current as of the load rather than backdated.",
            example = "2026-02-16",
            requiredMode = NOT_REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(
            description = "Last day of the assignment; open-ended when omitted",
            example = "2026-12-31",
            requiredMode = NOT_REQUIRED)
    private LocalDate effectiveTo;
}
