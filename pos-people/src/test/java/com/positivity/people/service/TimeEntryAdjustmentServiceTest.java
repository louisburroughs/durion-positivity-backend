package com.positivity.people.service;

import java.time.Clock;

import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.entity.TimeEntryAdjustment;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.service.TimeEntryAdjustmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.positivity.security.common.GatewaySecurityConstants;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("java:S100") // Test class is allowed to have non-camel-case method
								// names for readability
class TimeEntryAdjustmentServiceTest {

	private TimeEntryAdjustmentRepository adjustmentRepository;

	private TimeEntryAuditRepository auditRepository;

	private TimeEntryRepository timeEntryRepository;

	private TimeEntryAdjustmentService service;

	@BeforeEach
	void setup() {
		TestingAuthenticationToken authentication = new TestingAuthenticationToken("test-user", "password",
				"ROLE_USER");
		authentication.setDetails(java.util.Map.of(GatewaySecurityConstants.DETAIL_USER_ID,
				"22222222-2222-2222-2222-222222222222", GatewaySecurityConstants.DETAIL_USERNAME, "test-user"));
		authentication.setAuthenticated(true);
		SecurityContextHolder.getContext().setAuthentication(authentication);

		adjustmentRepository = mock(TimeEntryAdjustmentRepository.class);
		auditRepository = mock(TimeEntryAuditRepository.class);
		timeEntryRepository = mock(TimeEntryRepository.class);
		service = new TimeEntryAdjustmentServiceImpl(adjustmentRepository, auditRepository, timeEntryRepository,
				Clock.systemUTC());
	}

	@AfterEach
	void clearAuth() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void approveAdjustment_withPermission_succeeds() {
		UUID id = UUID.randomUUID();
		TimeEntryAdjustment adj = new TimeEntryAdjustment();
		adj.setAdjustmentId(id);
		adj.setTimeEntryId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.PENDING);

		when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));
		when(adjustmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

		boolean ok = service.approveAdjustment(id, "manager1", Set.of("people:timeAdjustment:approve"), "cid-1");
		assertTrue(ok);

		ArgumentCaptor<TimeEntryAdjustment> captor = ArgumentCaptor.forClass(TimeEntryAdjustment.class);
		verify(adjustmentRepository).save(captor.capture());
		TimeEntryAdjustment saved = captor.getValue();
		assertEquals(com.positivity.people.internal.enums.AdjustmentStatus.APPROVED, saved.getStatus());
		assertEquals("manager1", saved.getDecidedBy());

		verify(auditRepository).save(any(TimeEntryAudit.class));
	}

	@Test
	void approveAdjustment_withoutPermission_fails() {
		UUID id = UUID.randomUUID();
		TimeEntryAdjustment adj = new TimeEntryAdjustment();
		adj.setAdjustmentId(id);
		adj.setTimeEntryId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
		adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.PENDING);

		when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));
		Set<String> permissions = Set.of("people:timeEntry:approve");

		assertThrows(AccessDeniedException.class, () -> service.approveAdjustment(id, "user1", permissions, "cid-2"));
		verify(adjustmentRepository, never()).save(any());
		verify(auditRepository).save(any(TimeEntryAudit.class));
	}

	@Test
	void createAdjustment_missingTimeEntryId_throwsBadRequest() {
		TimeEntryAdjustmentRequest request = new TimeEntryAdjustmentRequest();
		request.setReasonCode("RC1");
		request.setMinutesDelta(5);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> service.createAdjustment(request));
		assertEquals("timeEntryId is required", ex.getMessage());
	}

	@Test
	void createAdjustment_timeEntryNotFound_throwsNotFound() {
		UUID timeEntryId = UUID.randomUUID();
		TimeEntryAdjustmentRequest request = new TimeEntryAdjustmentRequest();
		request.setReasonCode("RC1");
		request.setTimeEntryId(timeEntryId);
		request.setMinutesDelta(5);

		when(timeEntryRepository.findById(timeEntryId)).thenReturn(Optional.empty());

		NotFoundException ex = assertThrows(NotFoundException.class, () -> service.createAdjustment(request));
		assertEquals("Time entry not found", ex.getMessage());
	}

	@Test
	void createAdjustment_invalidState_throwsConflict() {
		UUID timeEntryId = UUID.randomUUID();
		TimeEntryAdjustmentRequest request = new TimeEntryAdjustmentRequest();
		request.setReasonCode("RC1");
		request.setTimeEntryId(timeEntryId);
		request.setMinutesDelta(5);

		TimeEntry entry = new TimeEntry();
		entry.setStatus(TimeEntryStatus.APPROVED);
		when(timeEntryRepository.findById(timeEntryId)).thenReturn(Optional.of(entry));

		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.createAdjustment(request));
		assertTrue(ex.getMessage().contains("PENDING_APPROVAL"));
	}

	@Test
	void approveAdjustment_notFound_throwsNotFound() {
		UUID id = UUID.randomUUID();
		when(adjustmentRepository.findById(id)).thenReturn(Optional.empty());
		Set<String> permissions = Set.of("people:timeAdjustment:approve");

		assertThrows(NotFoundException.class, () -> service.approveAdjustment(id, "manager1", permissions, "cid-1"));
	}

}
