package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.Data;

/**
 * Request to reactivate a DISABLED employee (the reverse of {@link DisableEmployeeRequestDto},
 * DECISION-PEOPLE-001).
 *
 * <p>Carries the optimistic-concurrency token, {@code updatedAt}: per DECISION-PEOPLE-017 (as
 * amended), any last-modified timestamp a resource already exposes satisfies the concurrency
 * requirement, so this reuses {@code EmployeeProfileDto.updatedAt} — the exact value most
 * recently read for the employee — rather than adding a second, duplicate timestamp field. The
 * field is required: this endpoint has no optional/unguarded path the way {@code
 * DisableEmployeeRequestDto} does, because reactivation is exactly the case DECISION-PEOPLE-017
 * exists for — restoring sign-in and staffing eligibility for an employee whose record may have
 * moved since the caller last read it.
 */
@Data
@Schema(description = "Request to reactivate a DISABLED employee, carrying the optimistic-concurrency token")
public class EnableEmployeeRequestDto {

    @Schema(
            description = "Concurrency token: the `updatedAt` value from the employee profile most recently read "
                    + "by the caller (EmployeeProfileDto.updatedAt). A value that no longer matches the "
                    + "employee's current updatedAt means the record changed since it was read, and the request "
                    + "is rejected with 409 rather than silently overwriting that change.",
            example = "2026-02-01T14:05:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Instant updatedAt;
}
