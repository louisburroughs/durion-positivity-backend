package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeStatusCountsResponse;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.EnableEmployeeRequestDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.enums.EmployeeSearchInclude;
import com.positivity.people.internal.enums.EmployeeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface EmployeeService {

    /**
     * Resolve an employee number to a slim identity projection (person id + status).
     * Case-insensitive. Returns empty when no employee matches.
     */
    @NonNull
    Optional<EmployeeIdentityDto> resolveByEmployeeNumber(@NonNull String employeeNumber);

    @NonNull
    EmployeeProfileDto createEmployee(@NonNull CreateEmployeeRequest request);

    @NonNull
    EmployeeProfileDto getEmployee(@NonNull UUID employeeId);

    @NonNull
    EmployeeProfileDto updateEmployee(@NonNull UUID employeeId, @NonNull UpdateEmployeeRequest request);

    @NonNull
    EmployeeProfileDto disableEmployee(@NonNull UUID employeeId, @NonNull DisableEmployeeRequestDto request);

    /**
     * Reactivate a DISABLED employee to ACTIVE (DECISION-PEOPLE-001's explicit DISABLED -&gt;
     * ACTIVE transition, the inverse of {@link #disableEmployee}). Guarded by the same {@code
     * updatedAt} concurrency token the profile already exposes (DECISION-PEOPLE-017); a mismatch,
     * a TERMINATED employee, an ON_LEAVE or SUSPENDED employee, or an already-ACTIVE employee all
     * raise {@link com.positivity.people.internal.exception.ResourceStateConflictException}
     * (409). Staffing assignments are left untouched — reactivation does not resurrect
     * assignments {@link #disableEmployee} ended.
     */
    @NonNull
    EmployeeProfileDto enableEmployee(@NonNull UUID employeeId, @NonNull EnableEmployeeRequestDto request);

    /**
     * Case-insensitive substring search across employee names (first, last, preferred, from the
     * identity replica) and employee number (local), optionally filtered by status and sorted,
     * merged/filtered/sorted/paged in memory (see {@link
     * com.positivity.people.internal.service.EmployeeServiceImpl#searchEmployees}). A blank or
     * null {@code q} lists every employee; a null or empty {@code status} applies no status
     * filter. {@code page} and {@code size} are already validated (non-negative page, size in
     * [1, 100]) by the controller; {@code status} filtering happens before {@code sort} and
     * before the page window is taken, so {@code totalElements} on the returned page always
     * reflects the filtered set.
     *
     * <p>Returns the flat {@link PagedResponse}, unchanged from before #2158 (corrected): the
     * status histogram for the register's stat tiles is a separate call, {@link
     * #employeeStatusCounts}, so a caller of this method that only ever passed {@code q}/{@code
     * page}/{@code size} — such as {@code HrFacadeTool.searchEmployees} — sees an identical
     * response shape.
     *
     * @param sort {@code "field,direction"} (Spring convention), e.g. {@code "lastName,desc"}.
     *     Null or blank defaults to {@code "lastName,asc"}. Only {@code lastName} is supported
     *     today; an unsupported field or direction raises {@link
     *     com.positivity.people.internal.exception.RequestValidationException}.
     * @param include (durion#2155) the register-enrichment categories to add to each returned
     *     row: username, PII-gated contact info, active application roles, primary location, and
     *     job role -- see {@link EmployeeSearchInclude}. Null or empty leaves every one of those
     *     fields null on {@link com.positivity.people.internal.dto.EmployeeSummaryDto}, the
     *     pre-#2155 thin shape. Whichever categories are requested are resolved with a fixed
     *     small number of batched queries against the page window taken by {@code page}/{@code
     *     size} -- never against the full q-/status-filtered result set -- so the response cost
     *     stays flat as the tenant's employee count grows.
     */
    @NonNull
    PagedResponse<EmployeeSummaryDto> searchEmployees(
            @Nullable String q,
            @Nullable List<EmployeeStatus> status,
            @Nullable String sort,
            int page,
            int size,
            @Nullable List<EmployeeSearchInclude> include);

    /**
     * Employee-status histogram for the register's stat tiles (durion#2158, corrected onto its
     * own endpoint — see {@link EmployeeStatusCountsResponse}'s javadoc for why). Computed over
     * exactly the same {@code q}-filtered set {@link #searchEmployees} matches, before any status
     * filter, so a tile for a status not currently selected on the search still reports what
     * selecting it would return; the counts always sum to the q-filtered total, including a bucket
     * for employees with no status recorded ({@link EmployeeStatusCountsResponse#UNKNOWN_STATUS}).
     * A blank or null {@code q} counts every employee.
     */
    @NonNull
    EmployeeStatusCountsResponse employeeStatusCounts(@Nullable String q);
}
