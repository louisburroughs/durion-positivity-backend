package com.positivity.people.controller;

import com.positivity.people.internal.controller.TimeEntryAdjustmentController;
import com.positivity.people.internal.dto.TimeEntryAdjustment;
import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.service.TimeEntryAdjustmentService;
import com.positivity.people.internal.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("java:S100") // Test class is allowed to have non-camel-case method
								// names for readability
class TimeEntryAdjustmentControllerTest {

	private TimeEntryAdjustmentService service;

	private TimeEntryAdjustmentController controller;

	@BeforeEach
	void setup() {
		service = mock(TimeEntryAdjustmentService.class);
		controller = new TimeEntryAdjustmentController(service);
	}

	@Test
	void listForTimeEntry_returnsList() {
		UUID timeEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		TimeEntryAdjustment a = new TimeEntryAdjustment();
		a.setAdjustmentId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
		a.setTimeEntryId(timeEntryId);
		when(service.listForTimeEntry(timeEntryId)).thenReturn(List.of(a));

		ResponseEntity<List<TimeEntryAdjustment>> resp = controller.listForTimeEntry(timeEntryId);
		assertEquals(200, resp.getStatusCode().value());
		assertNotNull(resp.getBody());
		assertEquals(1, resp.getBody().size());
	}

	@Test
	void createAdjustment_success_returnsOk() {
		UUID timeEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		TimeEntryAdjustmentRequest req = new TimeEntryAdjustmentRequest();
		req.setTimeEntryId(timeEntryId);
		req.setReasonCode("RC1");
		req.setMinutesDelta(15);
		req.setCreatedBy("tester");

		TimeEntryAdjustmentResponse created = new TimeEntryAdjustmentResponse(
				UUID.fromString("00000000-0000-0000-0000-000000000001"), true, "created");
		when(service.createAdjustment(req)).thenReturn(created);

		ResponseEntity<TimeEntryAdjustmentResponse> resp = controller.createAdjustment(req);
		assertEquals(201, resp.getStatusCode().value());
		assertNotNull(resp.getBody());
		assertTrue(resp.getBody().isSuccess());
		assertNotNull(resp.getBody().getAdjustmentId());
	}

	@Test
	void createAdjustment_failure_throwsExceptionForHandler() {
		UUID timeEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		TimeEntryAdjustmentRequest req = new TimeEntryAdjustmentRequest();
		req.setTimeEntryId(timeEntryId);
		req.setReasonCode(" ");

		when(service.createAdjustment(req)).thenThrow(new IllegalArgumentException("reasonCode is required"));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> controller.createAdjustment(req));
		assertEquals("reasonCode is required", ex.getMessage());
	}

	@Test
	void approveAdjustment_notFound_throwsExceptionForHandler() {
		UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		doThrow(new NotFoundException("Adjustment not found")).when(service)
				.approveAdjustment(eq(adjustmentId), anyString(), any());

		assertThrows(NotFoundException.class,
				() -> controller.approveAdjustment(adjustmentId, "cid-1"));
	}

	@Test
	void approveAdjustment_forbidden_throwsExceptionForHandler() {
		UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		doThrow(new AccessDeniedException("Permission denied")).when(service)
				.approveAdjustment(eq(adjustmentId), anyString(), any());

		assertThrows(AccessDeniedException.class,
				() -> controller.approveAdjustment(adjustmentId, "cid-1"));
	}

}
