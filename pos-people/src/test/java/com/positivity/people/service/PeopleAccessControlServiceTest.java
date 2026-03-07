package com.positivity.people.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.client.dto.RoleDto;
import com.positivity.people.internal.client.dto.UserRoleDto;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.service.PeopleAccessControlServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleAccessControlServiceTest {

	private SecurityServiceClient securityServiceClient;

	private UserPersonTranslationService userPersonTranslationService;

	private PersonRepository personRepository;

	private PeopleAccessControlService peopleAccessControlService;

	// Fixed UUIDs for deterministic tests
	private UUID testPersonId;

	private UUID testUserId;

	private UUID testLocationId;

	@BeforeEach
	void setUp() {
		securityServiceClient = mock(SecurityServiceClient.class);
		userPersonTranslationService = mock(UserPersonTranslationService.class);
		personRepository = mock(PersonRepository.class);
		peopleAccessControlService = new PeopleAccessControlServiceImpl(userPersonTranslationService,
				securityServiceClient, personRepository);

		// Initialize fixed test UUIDs
		testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		testUserId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000003");
	}

	@Test
	void getAvailableRolesForPerson_combinesLocationAndGlobalRoles() {
		RoleDto locationRole = RoleDto.builder().code("MANAGER").scopeType("LOCATION").build();
		RoleDto globalRole = RoleDto.builder().code("ADMIN").scopeType("GLOBAL").build();

		when(personRepository.existsById(testPersonId)).thenReturn(true);
		when(securityServiceClient.getAvailableRoles("LOCATION")).thenReturn(List.of(locationRole));
		when(securityServiceClient.getAvailableRoles("GLOBAL")).thenReturn(List.of(globalRole));

		List<RoleDto> result = peopleAccessControlService.getAvailableRolesForPerson(testPersonId);

		assertEquals(2, result.size());
		verify(personRepository).existsById(testPersonId);
	}

	@Test
	void getAvailableRolesForPerson_throwsWhenPersonNotFound() {
		when(personRepository.existsById(testPersonId)).thenReturn(false);

		assertThrows(PersonNotFoundException.class,
				() -> peopleAccessControlService.getAvailableRolesForPerson(testPersonId));
	}

	@Test
	void getPersonRoleAssignments_translatesPersonAndFetchesAssignments() {
		UserRoleDto assignment = UserRoleDto.builder().roleCode("MANAGER").build();
		when(userPersonTranslationService.getUserIdForPerson(testPersonId)).thenReturn(Optional.of(testUserId));
		when(securityServiceClient.getUserRoleAssignments(testUserId, true, null)).thenReturn(List.of(assignment));

		List<UserRoleDto> result = peopleAccessControlService.getPersonRoleAssignments(testPersonId, true, null);

		assertEquals(1, result.size());
		verify(userPersonTranslationService).getUserIdForPerson(testPersonId);
		verify(securityServiceClient).getUserRoleAssignments(testUserId, true, null);
	}

	@Test
	void assignRoleToPerson_translatesPersonAndCreatesAssignment() {
		UserRoleDto created = UserRoleDto.builder().roleCode("MANAGER").build();

		when(userPersonTranslationService.getUserIdForPerson(testPersonId)).thenReturn(Optional.of(testUserId));
		when(securityServiceClient.assignRole(any())).thenReturn(created);

		UserRoleDto result = peopleAccessControlService.assignRoleToPerson(testPersonId, "MANAGER", testLocationId,
				LocalDateTime.parse("2026-02-16T10:00:00"), null);

		assertEquals("MANAGER", result.getRoleCode());
		verify(securityServiceClient).assignRole(any());
	}

	@Test
	void revokeRoleFromPerson_callsSecurityClient() {
		LocalDateTime endDate = LocalDateTime.parse("2026-02-16T11:00:00");
		when(userPersonTranslationService.getUserIdForPerson(testPersonId)).thenReturn(Optional.of(testUserId));

		peopleAccessControlService.revokeRoleFromPerson(testPersonId, "MANAGER", endDate);

		verify(securityServiceClient).revokeRole(testUserId, "MANAGER", endDate);
	}

	@Test
	void personMethods_throwWhenNoUserLinkExists() {
		when(userPersonTranslationService.getUserIdForPerson(testPersonId)).thenReturn(Optional.empty());

		assertThrows(EntityNotFoundException.class,
				() -> peopleAccessControlService.getPersonRoleAssignments(testPersonId, false, null));
	}

	@Test
	void assignRoleToPerson_throwsWhenEndDateBeforeStartDate() {
		LocalDateTime startDate = LocalDateTime.parse("2026-02-20T10:00:00");
		LocalDateTime endDate = LocalDateTime.parse("2026-02-15T10:00:00"); // before
																			// startDate

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> peopleAccessControlService.assignRoleToPerson(testPersonId, "MANAGER", testLocationId, startDate,
						endDate));

		assertEquals("endDate must be greater than or equal to startDate", exception.getMessage());
	}

}