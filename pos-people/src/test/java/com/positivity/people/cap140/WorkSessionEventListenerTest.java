package com.positivity.people.cap140;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.people.internal.config.WorkSessionEventListener;
import com.positivity.people.internal.dto.WorkSessionCompletedEvent;
import com.positivity.people.internal.dto.WorkSessionCorrectedEvent;
import com.positivity.people.service.TimekeepingIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WorkSessionEventListener} verifying that Spring
 * application
 * events are correctly delegated to {@link TimekeepingIngestionService}
 * (CAP-140, Story
 * #58).
 *
 * Issue: #58
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S100")
class WorkSessionEventListenerTest {

	private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

	@Mock
	TimekeepingIngestionService timekeepingIngestionService;

	@InjectMocks
	WorkSessionEventListener workSessionEventListener;

	// -------------------------------------------------------------------------
	// AC6 – onWorkSessionCompleted delegates to ingestWorkSession
	// -------------------------------------------------------------------------

	@Test
	void onWorkSessionCompleted_delegatesToIngestWorkSession() {
		// Issue #58: AC6 — listener must call ingestWorkSession exactly once
		WorkSessionCompletedEvent event = new WorkSessionCompletedEvent(
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.now(TEST_CLOCK).minusSeconds(3600),
				Instant.now(TEST_CLOCK),
				UUID.fromString("00000000-0000-0000-0000-000000000001"));

		workSessionEventListener.onWorkSessionCompleted(event);

		verify(timekeepingIngestionService).ingestWorkSession(event);
	}

	// -------------------------------------------------------------------------
	// AC7 – onWorkSessionCorrected delegates to recordCorrection
	// -------------------------------------------------------------------------

	@Test
	void onWorkSessionCorrected_delegatesToRecordCorrection() {
		// Issue #58: AC7 — listener must call recordCorrection exactly once
		WorkSessionCorrectedEvent event = new WorkSessionCorrectedEvent(
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.now(TEST_CLOCK).minusSeconds(3600),
				Instant.now(TEST_CLOCK), null, "Test correction");

		workSessionEventListener.onWorkSessionCorrected(event);

		verify(timekeepingIngestionService).recordCorrection(event);
	}

}
