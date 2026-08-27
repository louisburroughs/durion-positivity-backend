package com.positivity.people.service;

import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
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
     * Case-insensitive substring search across employee names (first, last, preferred, from the
     * identity replica) and employee number (local), merged in memory (see {@link
     * com.positivity.people.internal.service.EmployeeServiceImpl#searchEmployees}). A blank or
     * null {@code q} lists every employee. {@code page} and {@code size} are already validated
     * (non-negative page, size in [1, 100]) by the controller.
     */
    @NonNull
    PagedResponse<EmployeeSummaryDto> searchEmployees(@Nullable String q, int page, int size);
}
