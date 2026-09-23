package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.people.internal.enums.EmployeeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope for {@code searchEmployees} (#2158): the requested page plus a status histogram for
 * the employee register's stat tiles.
 *
 * <p>This wraps {@link PagedResponse} rather than adding a field to it directly. {@code
 * PagedResponse} is this module's shared page envelope (also used by {@code
 * TimeEntryApprovalController} and {@code TimeEntryServiceImpl}); giving every page a histogram
 * field that only one endpoint populates would be a module-wide shape change to serve one
 * endpoint. A new type keeps that blast radius to the one endpoint that needs it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A page of employee search results, plus a status histogram over the q-filtered set")
public class EmployeeSearchResponse {

    @Schema(
            description = "Page of employee rows: q- and status-filtered, sorted, and windowed",
            requiredMode = REQUIRED)
    private PagedResponse<EmployeeSummaryDto> page;

    @Schema(
            description = "Employee count per status, for the register's stat tiles. Computed over the q-filtered set "
                    + "BEFORE the status filter, so a tile for a status the caller did not select still "
                    + "shows what selecting it would return, and the counts sum to the q-filtered total "
                    + "regardless of which statuses were requested — not to the (possibly smaller) "
                    + "status-filtered page total. An employee with no status recorded (the column is "
                    + "nullable; every create/update path requires one, so this is a defensive case rather "
                    + "than an expected one) is still returned by the search but cannot be classified into "
                    + "one of these buckets, so it is excluded here; the sum-to-total invariant holds "
                    + "whenever every matching employee has a status.",
            requiredMode = REQUIRED)
    private Map<EmployeeStatus, Long> statusCounts;
}
