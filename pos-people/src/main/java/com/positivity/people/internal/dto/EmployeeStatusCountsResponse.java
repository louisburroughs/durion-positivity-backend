package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for the employee-register status histogram (durion#2158, corrected): a status
 * breakdown for the same {@code q} filter {@code searchEmployees} applies, on its own sibling
 * endpoint rather than folded into the search response.
 *
 * <p>The original #2158 delivery wrapped {@code searchEmployees}'s existing {@code
 * PagedResponse<EmployeeSummaryDto>} return value in a new envelope carrying this histogram
 * alongside it, which changed the shape of every {@code searchEmployees} response — including
 * for a caller passing only {@code q}/{@code page}/{@code size}, which the issue's own acceptance
 * criteria required to stay unchanged (e.g. {@code HrFacadeTool.searchEmployees}). Splitting the
 * histogram onto its own endpoint restores {@code searchEmployees}'s original flat {@code
 * PagedResponse<EmployeeSummaryDto>} contract and gives the register a second call for the stat
 * tiles instead.
 *
 * <p><strong>Keyed by {@code String}, not {@link
 * com.positivity.people.internal.enums.EmployeeStatus}</strong> (also corrected from the original
 * delivery, which filtered null-status rows out of the map entirely). {@code Employee.status} is
 * nullable — on the entity, the V1 baseline column, and every legacy row created before status
 * became a required field on every create/update path — and a caller relying on "the counts sum
 * to the q-filtered total" would see that invariant silently break for a tenant carrying even one
 * such row. Keying by the enum has no slot for "no status recorded"; keying by {@code String}
 * does, via {@link #UNKNOWN_STATUS} — so a null-status row is counted somewhere rather than
 * vanishing, and the sum-to-total invariant holds unconditionally rather than only when every
 * matching employee happens to have a status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Employee status histogram over the q-filtered set, for the register's stat tiles")
public class EmployeeStatusCountsResponse {

    /**
     * Bucket key for an employee whose {@code status} column is {@code null} — a legacy row from
     * before status became a required field, never a value a create/update path writes on
     * purpose. Every employee the search matches lands in exactly one bucket, named or this one,
     * which is what keeps {@code counts.values()} always summing to the q-filtered total.
     */
    public static final String UNKNOWN_STATUS = "UNKNOWN";

    @Schema(
            description = "Employee count per status name (plus the " + "UNKNOWN"
                    + " bucket for a row with no status recorded), for the register's stat tiles. Computed over the "
                    + "q-filtered set — the same `q` match searchEmployees applies, before any status filter — so "
                    + "a tile for a status the caller has not selected on searchEmployees still shows what "
                    + "selecting it would return, and the counts always sum to the q-filtered total.",
            requiredMode = REQUIRED)
    private Map<String, Long> counts;
}
