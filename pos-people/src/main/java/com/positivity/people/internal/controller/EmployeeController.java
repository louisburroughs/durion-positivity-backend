package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/people/employees")
@RequiredArgsConstructor
@Tag(name = "Employee API", description = "Employee profile and offboarding operations")
public class EmployeeController {

	private final EmployeeService employeeService;

	@PostMapping
	@EmitEvent(id = "PEOPLE_EMPLOYEE_CREATE", apiVersion = "1")
	@Operation(summary = "Create employee profile")
	@ApiResponse(responseCode = "201", description = "Employee created")
	@ApiResponse(responseCode = "400", description = "Invalid request")
	@ApiResponse(responseCode = "409", description = "Duplicate employee")
	@ApiResponse(responseCode = "422", description = "Semantic validation failure")
	@PreAuthorize("hasAuthority('people:employee:create')")
	public ResponseEntity<EmployeeProfileDto> createEmployee(
			@Valid @RequestBody @NonNull CreateEmployeeRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.createEmployee(request));
	}

	@PutMapping("/{employeeId}")
	@EmitEvent(id = "PEOPLE_EMPLOYEE_UPDATE", apiVersion = "1")
	@Operation(summary = "Update employee profile")
	@ApiResponse(responseCode = "200", description = "Employee updated")
	@ApiResponse(responseCode = "400", description = "Invalid request")
	@ApiResponse(responseCode = "404", description = "Employee not found")
	@ApiResponse(responseCode = "409", description = "Duplicate employee")
	@ApiResponse(responseCode = "422", description = "Semantic validation failure")
	@PreAuthorize("hasAuthority('people:employee:edit')")
	public ResponseEntity<EmployeeProfileDto> updateEmployee(@PathVariable UUID employeeId,
			@Valid @RequestBody @NonNull UpdateEmployeeRequest request) {
		return ResponseEntity.ok(employeeService.updateEmployee(employeeId, request));
	}

	@GetMapping("/{employeeId}")
	@EmitEvent(id = "PEOPLE_EMPLOYEE_GET", apiVersion = "1")
	@Operation(summary = "Get employee profile")
	@ApiResponse(responseCode = "200", description = "Employee found")
	@ApiResponse(responseCode = "404", description = "Employee not found")
	@PreAuthorize("hasAuthority('people:employee:view')")
	public ResponseEntity<EmployeeProfileDto> getEmployee(@PathVariable UUID employeeId) {
		return ResponseEntity.ok(employeeService.getEmployee(employeeId));
	}

	@PostMapping("/{employeeId}/disable")
	@EmitEvent(id = "PEOPLE_EMPLOYEE_DISABLE", apiVersion = "1")
	@Operation(summary = "Disable employee profile")
	@ApiResponse(responseCode = "200", description = "Employee disabled")
	@ApiResponse(responseCode = "400", description = "Employee cannot be disabled")
	@ApiResponse(responseCode = "404", description = "Employee not found")
	@PreAuthorize("hasAuthority('people:employee:deactivate')")
	public ResponseEntity<EmployeeProfileDto> disableEmployee(@PathVariable UUID employeeId,
			@RequestBody(required = false) DisableEmployeeRequestDto request) {
		DisableEmployeeRequestDto resolved = request != null ? request : new DisableEmployeeRequestDto();
		return ResponseEntity.ok(employeeService.disableEmployee(employeeId, resolved));
	}

}
