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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
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
		adjustmentRepository = mock(TimeEntryAdjustmentRepository.class);
		auditRepository = mock(TimeEntryAuditRepository.class);
		timeEntryRepository = mock(TimeEntryRepository.class);
		service = new TimeEntryAdjustmentServiceImpl(adjustmentRepository, auditRepository, timeEntryRepository,
				Clock.systemUTC());
	}

	@Test
	void approveAdjustment_withPermission_succeeds() {
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
		TimeEntryAdjustment adj = new TimeEntryAdjustment();
		adj.setAdjustmentId(id);
		adj.setTimeEntry(new TimeEntry());
		adj.getTimeEntry().setTimeEntryId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.PENDING);

		when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));
		when(adjustmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

		boolean ok = service.approveAdjustment(id, "manager1", "cid-1");
		assertTrue(ok);

		ArgumentCaptor<TimeEntryAdjustment> captor = ArgumentCaptor.forClass(TimeEntryAdjustment.class);
		verify(adjustmentRepository).save(captor.capture());
		TimeEntryAdjustment saved = captor.getValue();
		assertEquals(com.positivity.people.internal.enums.AdjustmentStatus.APPROVED, saved.getStatus());
		assertEquals("manager1", saved.getDecidedBy());

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
		UUID timeEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
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
		UUID timeEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
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
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(adjustmentRepository.findById(id)).thenReturn(Optional.empty());
		assertThrows(NotFoundException.class, () -> service.approveAdjustment(id, "manager1", "cid-1"));
	}

}
