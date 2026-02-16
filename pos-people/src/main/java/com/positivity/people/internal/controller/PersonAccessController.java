package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.internal.dto.PersonRoleAssignmentRequest;
import com.positivity.people.service.PeopleAccessControlService;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/people")
public class PersonAccessController {

    private final PeopleAccessControlService peopleAccessControlService;

    public PersonAccessController(@NonNull PeopleAccessControlService peopleAccessControlService) {
        this.peopleAccessControlService = peopleAccessControlService;
    }

    @GetMapping("/{personUuid}/access/roles")
    @EmitEvent(id = "PEOPLE_ACCESS_ROLES_LIST", apiVersion = "1")
    public ResponseEntity<List<RoleDto>> getRoles(@PathVariable UUID personUuid) {
        return ResponseEntity.ok(peopleAccessControlService.getAvailableRolesForPerson(personUuid));
    }

    @GetMapping("/{personUuid}/access/assignments")
    @EmitEvent(id = "PEOPLE_ACCESS_ASSIGNMENTS_LIST", apiVersion = "1")
    public ResponseEntity<List<UserRoleDto>> getAssignments(
            @PathVariable UUID personUuid,
            @RequestParam(required = false) Boolean includeHistory,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity
                .ok(peopleAccessControlService.getPersonRoleAssignments(personUuid, includeHistory, endDate));
    }

    @PostMapping("/{personUuid}/access/assignments")
    @EmitEvent(id = "PEOPLE_ACCESS_ASSIGNMENT_CREATE", apiVersion = "1")
    public ResponseEntity<UserRoleDto> createAssignment(
            @PathVariable UUID personUuid,
            @Valid @RequestBody PersonRoleAssignmentRequest request) {
        UserRoleDto created = peopleAccessControlService.assignRoleToPerson(
                personUuid,
                request.getRoleCode(),
                request.getLocationId(),
                request.getStartDate(),
                request.getEndDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{personUuid}/access/assignments/{roleCode}")
    @EmitEvent(id = "PEOPLE_ACCESS_ASSIGNMENT_REVOKE", apiVersion = "1")
    public ResponseEntity<Void> revokeAssignment(
            @PathVariable UUID personUuid,
            @PathVariable String roleCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        peopleAccessControlService.revokeRoleFromPerson(personUuid, roleCode, endDate);
        return ResponseEntity.noContent().build();
    }
}