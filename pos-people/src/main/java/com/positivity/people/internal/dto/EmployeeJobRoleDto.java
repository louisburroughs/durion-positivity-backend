package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * The job role named on an employee profile (durion#2157): a small nested object rather than a
 * bare id or a flattened {@code jobRoleId}/{@code jobRoleName} pair on {@link EmployeeProfileDto}.
 *
 * <p>Bare id was rejected: a caller displaying an employee roster would otherwise have to fetch
 * the tenant's whole job-role list ({@code GET /v1/people/job-roles}) just to resolve one label,
 * for every employee on the page. Flattening the name onto {@code EmployeeProfileDto} directly
 * (the shape {@link PersonCredentialResponse} uses for {@code skillCode}/{@code competenceCode})
 * was rejected too: that DTO already nests one reference this way -- {@code contactInfo} -- so a
 * second nested object keeps the two consistent and leaves room to grow (e.g. {@code active}) on
 * the job role's own DTO shape without adding more top-level fields to the employee profile that
 * are not, in fact, employee attributes.
 */
@Value
@Builder
@Schema(description = "The job role named on an employee profile")
public class EmployeeJobRoleDto {

    @Schema(description = "Job role id", requiredMode = Schema.RequiredMode.REQUIRED)
    UUID id;

    @Schema(description = "Tenant's own short code", example = "LEAD_TECH", requiredMode = Schema.RequiredMode.REQUIRED)
    String code;

    @Schema(description = "Display name", example = "Lead Technician", requiredMode = Schema.RequiredMode.REQUIRED)
    String name;
}
