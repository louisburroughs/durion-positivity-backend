package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.WorkexecJobTimeTotalResponse;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedRequest;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerEntryResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerStartRequest;
import com.positivity.workorder.internal.dto.WorkexecTimerStopResponse;
import com.positivity.workorder.internal.service.WorkexecTimeTrackingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Workexec time-tracking endpoints: the mechanic is always the authenticated caller, replays are
 * flagged with {@code Idempotency-Replayed}, and every service failure gets its own status.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkexecTimeTrackingController")
class WorkexecTimeTrackingControllerTest {

    private static final UUID MECHANIC_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a01");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a02");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a03");
    private static final UUID ENTRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a04");
    private static final LocalDate START_DATE = LocalDate.of(2026, 3, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 3, 2);
    private static final String IDEMPOTENCY_KEY = "workexec-key-1";

    @Mock
    private WorkexecTimeTrackingService service;

    @InjectMocks
    private WorkexecTimeTrackingController controller;

    @BeforeEach
    void authenticateTheMechanic() {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "jane.smith",
                GatewaySecurityConstants.DETAIL_USER_ID,
                MECHANIC_ID));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithoutAUserId() {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static WorkexecTimeTrackingService.TimerEntry timerEntry(LocalDateTime endTime) {
        return new WorkexecTimeTrackingService.TimerEntry(
                ENTRY_ID,
                MECHANIC_ID,
                WORKORDER_ID,
                null,
                "BRAKE_JOB",
                LocalDateTime.of(2026, 3, 1, 8, 0),
                endTime,
                endTime == null ? null : 3600L,
                endTime == null ? "ACTIVE" : "COMPLETED");
    }

    private static WorkexecLaborPerformedRequest laborRequest() {
        return WorkexecLaborPerformedRequest.builder()
                .workorderId(WORKORDER_ID)
                .technicianId(MECHANIC_ID)
                .performedAt(Instant.parse("2026-03-01T15:00:00Z"))
                .labor(WorkexecLaborPerformedRequest.LaborQuantity.builder()
                        .quantity(new BigDecimal("1.5"))
                        .unit("HOURS")
                        .build())
                .source(WorkexecLaborPerformedRequest.SourceReference.builder()
                        .system("shopmgr")
                        .sourceReferenceId("ref-1")
                        .build())
                .build();
    }

    private static WorkexecTimeTrackingService.LaborPerformedResult laborResult(boolean replayed) {
        return new WorkexecTimeTrackingService.LaborPerformedResult(
                new WorkexecTimeTrackingService.LaborPerformedResponse(
                        ENTRY_ID,
                        WORKORDER_ID,
                        MECHANIC_ID,
                        Instant.parse("2026-03-01T15:00:00Z"),
                        new BigDecimal("1.50"),
                        "HOURS",
                        "shopmgr",
                        "ref-1"),
                replayed);
    }

    @Nested
    @DisplayName("getJobTimeTotals")
    class GetJobTimeTotals {

        @Test
        void mapsTheAggregatedTotalsOntoTheirResponse() {
            when(service.getJobTimeTotals(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(
                            new WorkexecTimeTrackingService.JobTimeTotal(MECHANIC_ID, LOCATION_ID, START_DATE, 210)));

            ResponseEntity<Object> response =
                    controller.getJobTimeTotals(START_DATE, END_DATE, "UTC", LOCATION_ID, List.of(MECHANIC_ID));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<WorkexecJobTimeTotalResponse> body = (List<WorkexecJobTimeTotalResponse>) response.getBody();
            assertThat(body).hasSize(1);
            assertThat(body.get(0).getTotalJobMinutes()).isEqualTo(210);
            assertThat(body.get(0).getTechnicianId()).isEqualTo(MECHANIC_ID);
        }

        @Test
        void treatsAMissingTechnicianFilterAsNoFilter() {
            when(service.getJobTimeTotals(any(), any(), any(), any(), any())).thenReturn(List.of());

            controller.getJobTimeTotals(START_DATE, END_DATE, "UTC", null, null);

            verify(service).getJobTimeTotals(eq(START_DATE), eq(END_DATE), any(), eq(null), eq(List.of()));
        }

        @Test
        void rejectsATimezoneThatIsNotAZoneId() {
            ResponseEntity<Object> response =
                    controller.getJobTimeTotals(START_DATE, END_DATE, "Mars/Olympus", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("Invalid timezone value");
        }

        @Test
        void rejectsAnInvertedDateRange() {
            ResponseEntity<Object> response = controller.getJobTimeTotals(END_DATE, START_DATE, "UTC", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("endDate must be on or after startDate");
        }
    }

    @Nested
    @DisplayName("createLaborPerformed")
    class CreateLaborPerformed {

        @Test
        void reportsCreatedForANewLaborPosting() {
            when(service.recordLaborPerformed(any(), anyString())).thenReturn(laborResult(false));

            ResponseEntity<Object> response =
                    controller.createLaborPerformed(laborRequest(), IDEMPOTENCY_KEY, "corr-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-1");
            assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isNull();
            assertThat(((WorkexecLaborPerformedResponse) response.getBody()).getQuantity())
                    .isEqualByComparingTo("1.50");
        }

        @Test
        void flagsAReplayWithItsOwnHeaderAndAnOkStatus() {
            when(service.recordLaborPerformed(any(), anyString())).thenReturn(laborResult(true));

            ResponseEntity<Object> response = controller.createLaborPerformed(laborRequest(), IDEMPOTENCY_KEY, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNull();
        }

        @Test
        void requiresAnIdempotencyKey() {
            ResponseEntity<Object> response = controller.createLaborPerformed(laborRequest(), "  ", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("Idempotency-Key header is required");
            verify(service, never()).recordLaborPerformed(any(), anyString());
        }

        @Test
        void mapsEveryServiceFailureOntoItsOwnStatus() {
            doThrow(new NoSuchElementException("Workorder not found"))
                    .when(service)
                    .recordLaborPerformed(any(), anyString());
            assertThat(controller
                            .createLaborPerformed(laborRequest(), IDEMPOTENCY_KEY, null)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new WorkexecTimeTrackingService.WorkexecConflictException(
                            "WORKEXEC_CONFLICT_WORKORDER_STATE", "cancelled"))
                    .when(service)
                    .recordLaborPerformed(any(), anyString());
            ResponseEntity<Object> conflict = controller.createLaborPerformed(laborRequest(), IDEMPOTENCY_KEY, null);
            assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.getBody().toString()).contains("WORKEXEC_CONFLICT_WORKORDER_STATE");

            doThrow(new IllegalArgumentException("labor.unit must be HOURS"))
                    .when(service)
                    .recordLaborPerformed(any(), anyString());
            assertThat(controller
                            .createLaborPerformed(laborRequest(), IDEMPOTENCY_KEY, null)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("timers")
    class Timers {

        @Test
        void listsTheAuthenticatedMechanicsActiveTimers() {
            when(service.getActiveTimers(MECHANIC_ID)).thenReturn(List.of(timerEntry(null)));

            ResponseEntity<Object> response = controller.getActiveTimerEntries();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<WorkexecTimerEntryResponse> body = (List<WorkexecTimerEntryResponse>) response.getBody();
            assertThat(body).hasSize(1);
            assertThat(body.get(0).getStatus()).isEqualTo("ACTIVE");
            assertThat(body.get(0).getLaborCode()).isEqualTo("BRAKE_JOB");
        }

        @Test
        void startsATimerAndReportsCreated() {
            when(service.startTimer(eq(MECHANIC_ID), any(), any()))
                    .thenReturn(new WorkexecTimeTrackingService.TimerStartResult(timerEntry(null), false));

            ResponseEntity<Object> response = controller.startTimer(
                    IDEMPOTENCY_KEY,
                    WorkexecTimerStartRequest.builder()
                            .workorderId(WORKORDER_ID)
                            .laborCode("BRAKE_JOB")
                            .build());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        }

        @Test
        void flagsAReplayedTimerStart() {
            when(service.startTimer(eq(MECHANIC_ID), any(), any()))
                    .thenReturn(new WorkexecTimeTrackingService.TimerStartResult(timerEntry(null), true));

            ResponseEntity<Object> response = controller.startTimer(
                    IDEMPOTENCY_KEY,
                    WorkexecTimerStartRequest.builder()
                            .workorderId(WORKORDER_ID)
                            .build());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        }

        @Test
        void mapsTimerStartFailuresOntoNotFoundAndConflict() {
            WorkexecTimerStartRequest request = WorkexecTimerStartRequest.builder()
                    .workorderId(WORKORDER_ID)
                    .build();

            doThrow(new NoSuchElementException("Workorder not found"))
                    .when(service)
                    .startTimer(any(), any(), any());
            assertThat(controller.startTimer(null, request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            doThrow(new WorkexecTimeTrackingService.WorkexecConflictException(
                            "TIMER_ALREADY_ACTIVE", "already running"))
                    .when(service)
                    .startTimer(any(), any(), any());
            ResponseEntity<Object> conflict = controller.startTimer(null, request);
            assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.getBody().toString()).contains("TIMER_ALREADY_ACTIVE");
        }

        @Test
        void stopsEveryTimerAndReturnsThem() {
            when(service.stopTimers(MECHANIC_ID)).thenReturn(List.of(timerEntry(LocalDateTime.of(2026, 3, 1, 9, 0))));

            ResponseEntity<Object> response = controller.stopTimers();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((WorkexecTimerStopResponse) response.getBody()).getStopped())
                    .hasSize(1);
        }

        @Test
        void reportsAConflictWhenNoTimerIsRunning() {
            doThrow(new WorkexecTimeTrackingService.WorkexecConflictException("NO_ACTIVE_TIMER", "nothing running"))
                    .when(service)
                    .stopTimers(MECHANIC_ID);

            ResponseEntity<Object> response = controller.stopTimers();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().toString()).contains("NO_ACTIVE_TIMER");
        }

        @Test
        void rejectsAContextWithoutAUuidUserIdAsABadRequest() {
            // SecurityContextHelper is fail-fast: it raises MissingPersonIdException rather than
            // returning an empty Optional. The controller catches it so the caller gets the 400
            // these endpoints document, not the 409 the module's IllegalStateException advice
            // would otherwise produce.
            authenticateWithoutAUserId();
            WorkexecTimerStartRequest request = WorkexecTimerStartRequest.builder()
                    .workorderId(WORKORDER_ID)
                    .build();

            assertThat(controller.getActiveTimerEntries()).satisfies(this::isUserIdRequiredBadRequest);
            assertThat(controller.startTimer(null, request)).satisfies(this::isUserIdRequiredBadRequest);
            assertThat(controller.stopTimers()).satisfies(this::isUserIdRequiredBadRequest);
            verify(service, never()).getActiveTimers(any());
            verify(service, never()).startTimer(any(), any(), any());
            verify(service, never()).stopTimers(any());
        }

        private void isUserIdRequiredBadRequest(ResponseEntity<Object> response) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody())
                    .isEqualTo(Map.of(
                            "code", "WORKEXEC_INVALID_REQUEST",
                            "message", "Authenticated user id must be a valid UUID"));
        }
    }
}
