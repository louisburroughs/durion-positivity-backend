package com.positivity.people.service;

import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface EmployeeService {

    @NonNull
    EmployeeProfileDto createEmployee(@NonNull CreateEmployeeRequest request);

    @NonNull
    EmployeeProfileDto getEmployee(@NonNull UUID employeeId);

    @NonNull
    EmployeeProfileDto updateEmployee(@NonNull UUID employeeId, @NonNull UpdateEmployeeRequest request);

    @NonNull
    EmployeeProfileDto disableEmployee(@NonNull UUID employeeId, @NonNull DisableEmployeeRequestDto request);
}
