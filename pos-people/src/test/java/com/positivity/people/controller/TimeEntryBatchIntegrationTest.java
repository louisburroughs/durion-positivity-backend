package com.positivity.people.controller;

import com.positivity.people.internal.controller.TimeEntryApprovalController;
import com.positivity.people.internal.dto.TimeEntryDecisionBatchRequest;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.service.TimeEntryServiceImpl;
import com.positivity.people.service.TimeEntryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.positivity.security.common.GatewaySecurityConstants;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TimeEntryBatchIntegrationTest {

	private static final Clock TEST_CLOCK = Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"),
			java.time.ZoneOffset.UTC);

	private TimeEntryRepository entryRepository;

	private TimeEntryAuditRepository auditRepository;

	private TimeEntryService timeEntryService;

	private TimeEntryApprovalController controller;

	@BeforeEach
	void setup() {
		TestingAuthenticationToken authentication = new TestingAuthenticationToken("test-user", "password",
				"ROLE_USER");
		authentication.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USER_ID,
				"11111111-1111-1111-1111-111111111111", GatewaySecurityConstants.DETAIL_USERNAME, "test-user"));
		authentication.setAuthenticated(true);
		SecurityContextHolder.getContext().setAuthentication(authentication);

		entryRepository = mock(TimeEntryRepository.class);
		auditRepository = mock(TimeEntryAuditRepository.class);
		timeEntryService = new TimeEntryServiceImpl(TEST_CLOCK, entryRepository, auditRepository);
		controller = new TimeEntryApprovalController(timeEntryService);
	}

	@AfterEach
	void clearAuth() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void approveTimeEntries_withValidEntries_succeeds() {
		java.util.UUID entryId1 = java.util.UUID.randomUUID();
		java.util.UUID entryId2 = java.util.UUID.randomUUID();

		TimeEntry entry1 = new TimeEntry();
		entry1.setTimeEntryId(entryId1);
		entry1.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL);

		TimeEntry entry2 = new TimeEntry();
		entry2.setTimeEntryId(entryId2);
		entry2.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL);

		when(entryRepository.findByTimeEntryIdIn(List.of(entryId1, entryId2))).thenReturn(List.of(entry1, entry2));
		when(entryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

		TimeEntryDecisionBatchRequest request = new TimeEntryDecisionBatchRequest();
		TimeEntryDecisionBatchRequest.Decision d1 = new TimeEntryDecisionBatchRequest.Decision();
		d1.setTimeEntryId(entryId1.toString());
		TimeEntryDecisionBatchRequest.Decision d2 = new TimeEntryDecisionBatchRequest.Decision();
		d2.setTimeEntryId(entryId2.toString());
		request.setDecisions(Arrays.asList(d1, d2));

		ResponseEntity<?> response = controller.approveTimeEntries(request, "cid-001");
		assertEquals(200, response.getStatusCode().value());
	}

	@Test
	void rejectTimeEntries_withoutReasonForEntry_returnsBadRequest() {
		TimeEntryDecisionBatchRequest request = new TimeEntryDecisionBatchRequest();
		TimeEntryDecisionBatchRequest.Decision d1 = new TimeEntryDecisionBatchRequest.Decision();
		d1.setTimeEntryId(java.util.UUID.randomUUID().toString());
		d1.setRejectionReason(null);
		request.setDecisions(Arrays.asList(d1));

		// The validation logic moved to the service throwing IllegalArgumentException, OR
		// it will be caught by
		// PeopleExceptionHandler as @Valid handles MethodArgumentNotValidException.
		// For a unit test directly calling the controller without Spring Boot Web
		// resolving @Valid, it invokes the method.
		// Therefore, it delegates to the service which will throw an
		// IllegalArgumentException.
		assertThrows(IllegalArgumentException.class, () -> {
			controller.rejectTimeEntries(request, "cid-002");
		});
	}

	@Test
	void rejectTimeEntries_withValidReasons_succeeds() {
		java.util.UUID entryId = java.util.UUID.randomUUID();
		TimeEntry entry = new TimeEntry();
		entry.setTimeEntryId(entryId);
		entry.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL);

		when(entryRepository.findByTimeEntryIdIn(List.of(entryId))).thenReturn(List.of(entry));
		when(entryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

		TimeEntryDecisionBatchRequest request = new TimeEntryDecisionBatchRequest();
		TimeEntryDecisionBatchRequest.Decision d1 = new TimeEntryDecisionBatchRequest.Decision();
		d1.setTimeEntryId(entryId.toString());
		d1.setRejectionReason("Time discrepancy");
		request.setDecisions(Arrays.asList(d1));

		ResponseEntity<?> response = controller.rejectTimeEntries(request, "cid-003");
		assertEquals(200, response.getStatusCode().value());
	}

}
