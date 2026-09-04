package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Workexec time tracking: job-time totals bucket finalized labor into local dates, labor
 * postings and timer starts replay on their idempotency key, and attributing a timer to anyone
 * other than yourself or the current assignee needs the on-behalf authority plus a reason.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkexecTimeTrackingServiceImpl")
class WorkexecTimeTrackingServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01");
    private static final UUID TECHNICIAN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a02");
    private static final UUID OTHER_TECHNICIAN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a03");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a04");
    private static final UUID SERVICE_LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a05");
    private static final UUID ENTRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a06");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String IDEMPOTENCY_KEY = "workexec-key-1";
    private static final String ON_BEHALF_AUTHORITY = "workorder:labor:add_on_behalf";

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderLaborEntryRepository laborEntryRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private TechnicianAssignmentRepository technicianAssignmentRepository;

    @Mock
    private IdempotencyService idempotencyService;

    private WorkexecTimeTrackingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkexecTimeTrackingServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                workorderRepository,
                laborEntryRepository,
                workorderServiceRepository,
                technicianAssignmentRepository,
                idempotencyService);
        when(laborEntryRepository.save(any())).thenAnswer(invocation -> {
            WorkorderLaborEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(ENTRY_ID);
            }
            return entry;
        });
        when(workorderServiceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workorderServiceRepository.findByWorkOrder_Id(WORKORDER_ID)).thenReturn(List.of(serviceLine()));
        when(idempotencyService.getExistingLaborEntryId(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String username, String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static Workorder workorder(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setShopId(LOCATION_ID);
        workorder.setStatus(status);
        return workorder;
    }

    private static WorkorderServiceLine serviceLine() {
        return WorkorderServiceLine.builder()
                .id(SERVICE_LINE_ID)
                .workOrder(workorder(WorkorderStatus.APPROVED))
                .build();
    }

    private static WorkorderLaborEntry laborEntry(LocalDateTime start, LocalDateTime end, String hours) {
        return WorkorderLaborEntry.builder()
                .id(ENTRY_ID)
                .workorder(workorder(WorkorderStatus.COMPLETED))
                .workorderService(serviceLine())
                .technicianId(TECHNICIAN_ID)
                .startTime(start)
                .endTime(end)
                .hoursWorked(hours == null ? null : new BigDecimal(hours))
                .notes("BRAKE_JOB")
                .createdBy("system")
                .build();
    }

    private void stubWorkorder(WorkorderStatus status) {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder(status)));
    }

    private WorkorderLaborEntry savedEntry() {
        ArgumentCaptor<WorkorderLaborEntry> saved = ArgumentCaptor.forClass(WorkorderLaborEntry.class);
        verify(laborEntryRepository).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("getJobTimeTotals")
    class GetJobTimeTotals {

        private List<WorkexecTimeTrackingService.JobTimeTotal> totals(
                ZoneId zone, UUID locationFilter, List<UUID> technicianFilter) {
            return service.getJobTimeTotals(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), zone, locationFilter, technicianFilter);
        }

        @Test
        void sumsLaborMinutesPerTechnicianLocationAndLocalDate() {
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(
                            laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), LocalDateTime.of(2026, 3, 1, 10, 0), "2.0"),
                            laborEntry(
                                    LocalDateTime.of(2026, 3, 1, 13, 0), LocalDateTime.of(2026, 3, 1, 14, 30), "1.5")));

            List<WorkexecTimeTrackingService.JobTimeTotal> totals = totals(ZoneOffset.UTC, null, List.of());

            assertThat(totals).hasSize(1);
            assertThat(totals.get(0).technicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(totals.get(0).locationId()).isEqualTo(LOCATION_ID);
            assertThat(totals.get(0).localDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(totals.get(0).totalJobMinutes()).isEqualTo(210);
        }

        @Test
        void bucketsByTheRequestedTimezoneRatherThanUtc() {
            // 02:00Z on 2 March is still 1 March in New York.
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(
                            laborEntry(LocalDateTime.of(2026, 3, 2, 1, 0), LocalDateTime.of(2026, 3, 2, 2, 0), "1.0")));

            List<WorkexecTimeTrackingService.JobTimeTotal> totals =
                    totals(ZoneId.of("America/New_York"), null, List.of());

            assertThat(totals).hasSize(1);
            assertThat(totals.get(0).localDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        void dropsEntriesOutsideTheRequestedWindow() {
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(
                            laborEntry(LocalDateTime.of(2026, 2, 28, 8, 0), LocalDateTime.of(2026, 2, 28, 9, 0), "1.0"),
                            laborEntry(LocalDateTime.of(2026, 3, 3, 8, 0), LocalDateTime.of(2026, 3, 3, 9, 0), "1.0")));

            assertThat(totals(ZoneOffset.UTC, null, List.of())).isEmpty();
        }

        @Test
        void dropsAnEntryThatWasNeverFinished() {
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), null, "1.0")));

            assertThat(totals(ZoneOffset.UTC, null, List.of())).isEmpty();
        }

        @Test
        void filtersToTheRequestedTechnicians() {
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(
                            laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), LocalDateTime.of(2026, 3, 1, 9, 0), "1.0")));

            assertThat(totals(ZoneOffset.UTC, null, List.of(OTHER_TECHNICIAN_ID)))
                    .isEmpty();
            assertThat(totals(ZoneOffset.UTC, null, List.of(TECHNICIAN_ID))).hasSize(1);
        }

        @Test
        void filtersToTheRequestedLocation() {
            WorkorderLaborEntry entry =
                    laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), LocalDateTime.of(2026, 3, 1, 9, 0), "1.0");
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(entry));

            assertThat(totals(ZoneOffset.UTC, LOCATION_ID, List.of())).hasSize(1);
            assertThat(totals(ZoneOffset.UTC, OTHER_TECHNICIAN_ID, List.of())).isEmpty();
        }

        @Test
        void dropsAnEntryWithNoLocationWhenALocationFilterIsApplied() {
            WorkorderLaborEntry entry =
                    laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), LocalDateTime.of(2026, 3, 1, 9, 0), "1.0");
            entry.getWorkorder().setShopId(null);
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(entry));

            assertThat(totals(ZoneOffset.UTC, LOCATION_ID, List.of())).isEmpty();
        }

        @Test
        void ordersTheResultByDateThenTechnician() {
            WorkorderLaborEntry laterDay =
                    laborEntry(LocalDateTime.of(2026, 3, 2, 8, 0), LocalDateTime.of(2026, 3, 2, 9, 0), "1.0");
            WorkorderLaborEntry earlierDay =
                    laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), LocalDateTime.of(2026, 3, 1, 9, 0), "1.0");
            when(laborEntryRepository.findFinalizedByEndTimeBetween(any(), any(), any()))
                    .thenReturn(List.of(laterDay, earlierDay));

            List<WorkexecTimeTrackingService.JobTimeTotal> totals = totals(ZoneOffset.UTC, null, List.of());

            assertThat(totals).hasSize(2);
            assertThat(totals.get(0).localDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        }
    }

    @Nested
    @DisplayName("recordLaborPerformed")
    class RecordLaborPerformed {

        private WorkexecTimeTrackingService.LaborPerformedRequest request(String quantity, String unit) {
            return new WorkexecTimeTrackingService.LaborPerformedRequest(
                    WORKORDER_ID,
                    TECHNICIAN_ID,
                    Instant.parse("2026-03-01T15:00:00Z"),
                    new WorkexecTimeTrackingService.LaborQuantity(
                            quantity == null ? null : new BigDecimal(quantity), unit),
                    new WorkexecTimeTrackingService.SourceReference("shopmgr", "ref-1"));
        }

        @Test
        void postsTheLaborEntryBackdatedByTheHoursWorked() {
            stubWorkorder(WorkorderStatus.WORK_IN_PROGRESS);

            WorkexecTimeTrackingService.LaborPerformedResult result =
                    service.recordLaborPerformed(request("1.5", "HOURS"), IDEMPOTENCY_KEY);

            WorkorderLaborEntry saved = savedEntry();
            assertThat(saved.getEndTime()).isEqualTo(LocalDateTime.of(2026, 3, 1, 15, 0));
            assertThat(saved.getStartTime()).isEqualTo(LocalDateTime.of(2026, 3, 1, 13, 30));
            assertThat(saved.getHoursWorked()).isEqualByComparingTo("1.50");
            assertThat(saved.getNotes()).isEqualTo("sourceSystem=shopmgr;sourceReferenceId=ref-1");
            assertThat(result.replayed()).isFalse();
            assertThat(result.response().sourceSystem()).isEqualTo("shopmgr");
            verify(idempotencyService)
                    .registerLaborKey(anyString(), org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY), any());
        }

        @Test
        void replaysTheOriginalEntryForAKnownIdempotencyKey() {
            when(idempotencyService.getExistingLaborEntryId(anyString(), anyString()))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborEntryRepository.findById(ENTRY_ID))
                    .thenReturn(Optional.of(laborEntry(
                            LocalDateTime.of(2026, 3, 1, 13, 30), LocalDateTime.of(2026, 3, 1, 15, 0), "1.50")));

            WorkexecTimeTrackingService.LaborPerformedResult result =
                    service.recordLaborPerformed(request("1.5", "HOURS"), IDEMPOTENCY_KEY);

            assertThat(result.replayed()).isTrue();
            verify(laborEntryRepository, never()).save(any());
        }

        @Test
        void failsWhenTheReplayedEntryIsGone() {
            when(idempotencyService.getExistingLaborEntryId(anyString(), anyString()))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());
            WorkexecTimeTrackingService.LaborPerformedRequest request = request("1.5", "HOURS");

            assertThatThrownBy(() -> service.recordLaborPerformed(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        void failsForAnUnknownWorkorder() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());
            WorkexecTimeTrackingService.LaborPerformedRequest request = request("1.5", "HOURS");

            assertThatThrownBy(() -> service.recordLaborPerformed(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        void refusesLaborOnACancelledWorkorder() {
            stubWorkorder(WorkorderStatus.CANCELLED);
            WorkexecTimeTrackingService.LaborPerformedRequest request = request("1.5", "HOURS");

            assertThatThrownBy(() -> service.recordLaborPerformed(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(WorkexecTimeTrackingService.WorkexecConflictException.class);
        }

        @Test
        void refusesLaborOnACompletedWorkorderUnlessItWasReopened() {
            Workorder completed = workorder(WorkorderStatus.COMPLETED);
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(completed));
            WorkexecTimeTrackingService.LaborPerformedRequest request = request("1.5", "HOURS");

            assertThatThrownBy(() -> service.recordLaborPerformed(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(WorkexecTimeTrackingService.WorkexecConflictException.class);

            completed.setIsReopened(true);
            assertThat(service.recordLaborPerformed(request, IDEMPOTENCY_KEY)).isNotNull();
        }

        @Test
        void refusesAUnitOtherThanHours() {
            stubWorkorder(WorkorderStatus.APPROVED);
            WorkexecTimeTrackingService.LaborPerformedRequest request = request("1.5", "MINUTES");

            assertThatThrownBy(() -> service.recordLaborPerformed(request, IDEMPOTENCY_KEY))
                    .isInstanceOf(WorkorderRequestValidationException.class)
                    .hasMessageContaining("labor.unit must be HOURS");
        }

        @Test
        void refusesANonPositiveOrMissingQuantity() {
            stubWorkorder(WorkorderStatus.APPROVED);

            assertThatThrownBy(() -> service.recordLaborPerformed(request("0", "HOURS"), IDEMPOTENCY_KEY))
                    .isInstanceOf(WorkorderRequestValidationException.class);
            assertThatThrownBy(() -> service.recordLaborPerformed(request(null, "HOURS"), IDEMPOTENCY_KEY))
                    .isInstanceOf(WorkorderRequestValidationException.class);
        }

        @Test
        void createsAServiceLineWhenTheWorkorderHasNoneYet() {
            stubWorkorder(WorkorderStatus.APPROVED);
            when(workorderServiceRepository.findByWorkOrder_Id(WORKORDER_ID)).thenReturn(List.of());

            service.recordLaborPerformed(request("1.0", "HOURS"), IDEMPOTENCY_KEY);

            ArgumentCaptor<WorkorderServiceLine> created = ArgumentCaptor.forClass(WorkorderServiceLine.class);
            verify(workorderServiceRepository).save(created.capture());
            assertThat(created.getValue().getDescription()).isEqualTo("Workexec auto-created labor service");
        }
    }

    @Nested
    @DisplayName("startTimer")
    class StartTimer {

        /**
         * The timer stamps {@code createdBy} from the security context, so a realistic caller is
         * always authenticated — the gateway guarantees it for every timer endpoint.
         */
        @BeforeEach
        void authenticateTheActor() {
            authenticateAs("jane.smith");
        }

        private WorkexecTimeTrackingService.TimerStartRequest request(UUID technicianId, String reason) {
            return new WorkexecTimeTrackingService.TimerStartRequest(
                    WORKORDER_ID, null, "BRAKE_JOB", technicianId, reason);
        }

        @Test
        void startsATimerForTheActorThemselves() {
            stubWorkorder(WorkorderStatus.APPROVED);
            when(laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(TECHNICIAN_ID))
                    .thenReturn(List.of());

            WorkexecTimeTrackingService.TimerStartResult result =
                    service.startTimer(TECHNICIAN_ID, request(null, null), null);

            WorkorderLaborEntry saved = savedEntry();
            assertThat(saved.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(saved.getActedByUserId()).isNull();
            assertThat(saved.getOnBehalfReason()).isNull();
            assertThat(saved.getHoursWorked()).isEqualByComparingTo("0");
            assertThat(result.replayed()).isFalse();
            assertThat(result.response().status()).isEqualTo("ACTIVE");
        }

        @Test
        void defaultsAttributionToTheCurrentAssigneeAndRecordsTheInitiator() {
            stubWorkorder(WorkorderStatus.ASSIGNED);
            TechnicianAssignment assignment = new TechnicianAssignment();
            assignment.setTechnicianId(OTHER_TECHNICIAN_ID);
            when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(assignment));
            when(laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(OTHER_TECHNICIAN_ID))
                    .thenReturn(List.of());

            service.startTimer(TECHNICIAN_ID, request(null, null), null);

            WorkorderLaborEntry saved = savedEntry();
            assertThat(saved.getTechnicianId()).isEqualTo(OTHER_TECHNICIAN_ID);
            assertThat(saved.getActedByUserId()).isEqualTo(TECHNICIAN_ID);
            // The default-attribution path is not an on-behalf action, so no reason is recorded.
            assertThat(saved.getOnBehalfReason()).isNull();
        }

        @Test
        void requiresTheOnBehalfAuthorityToTrackAThirdTechnician() {
            stubWorkorder(WorkorderStatus.APPROVED);
            authenticateAs("jane.smith");

            WorkexecTimeTrackingService.TimerStartRequest request = request(OTHER_TECHNICIAN_ID, "covering the shift");

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void requiresAReasonForAnOnBehalfTimer() {
            stubWorkorder(WorkorderStatus.APPROVED);
            authenticateAs("jane.smith", ON_BEHALF_AUTHORITY);

            WorkexecTimeTrackingService.TimerStartRequest request = request(OTHER_TECHNICIAN_ID, "  ");

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(WorkorderRequestValidationException.class)
                    .hasMessageContaining("reason is required");
        }

        @Test
        void recordsTheReasonOnAnAuthorizedOnBehalfTimer() {
            stubWorkorder(WorkorderStatus.APPROVED);
            authenticateAs("jane.smith", ON_BEHALF_AUTHORITY);
            when(laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(OTHER_TECHNICIAN_ID))
                    .thenReturn(List.of());

            service.startTimer(TECHNICIAN_ID, request(OTHER_TECHNICIAN_ID, "covering the shift"), null);

            WorkorderLaborEntry saved = savedEntry();
            assertThat(saved.getTechnicianId()).isEqualTo(OTHER_TECHNICIAN_ID);
            assertThat(saved.getOnBehalfReason()).isEqualTo("covering the shift");
            assertThat(saved.getActedByUserId()).isEqualTo(TECHNICIAN_ID);
            assertThat(saved.getCreatedBy()).isEqualTo("jane.smith");
        }

        @Test
        void refusesASecondTimerForTheSameTrackedTechnician() {
            stubWorkorder(WorkorderStatus.APPROVED);
            when(laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(TECHNICIAN_ID))
                    .thenReturn(List.of(laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), null, "0")));
            WorkexecTimeTrackingService.TimerStartRequest request = request(null, null);

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(WorkexecTimeTrackingService.WorkexecConflictException.class)
                    .hasMessageContaining("active timer");
        }

        @Test
        void refusesATimerOnAWorkorderThatIsNotEligible() {
            stubWorkorder(WorkorderStatus.COMPLETED);
            WorkexecTimeTrackingService.TimerStartRequest request = request(null, null);

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(WorkexecTimeTrackingService.WorkexecConflictException.class)
                    .hasMessageContaining("timer-eligible");
        }

        @Test
        void failsForAnUnknownWorkorder() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());
            WorkexecTimeTrackingService.TimerStartRequest request = request(null, null);

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        void replaysTheOriginalTimerForAKnownIdempotencyKey() {
            when(idempotencyService.getExistingLaborEntryId(anyString(), anyString()))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborEntryRepository.findById(ENTRY_ID))
                    .thenReturn(Optional.of(laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), null, "0")));

            WorkexecTimeTrackingService.TimerStartResult result =
                    service.startTimer(TECHNICIAN_ID, request(null, null), IDEMPOTENCY_KEY);

            assertThat(result.replayed()).isTrue();
            verify(laborEntryRepository, never()).save(any());
        }

        @Test
        void failsWhenTheReplayedTimerIsGone() {
            when(idempotencyService.getExistingLaborEntryId(anyString(), anyString()))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborEntryRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());
            WorkexecTimeTrackingService.TimerStartRequest request = request(null, null);

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, IDEMPOTENCY_KEY))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        void ignoresABlankIdempotencyKey() {
            stubWorkorder(WorkorderStatus.APPROVED);

            service.startTimer(TECHNICIAN_ID, request(null, null), "   ");

            verify(idempotencyService, never()).registerLaborKey(anyString(), anyString(), any());
        }

        @Test
        void pinsAnExplicitWorkorderItemAndRejectsOneFromAnotherWorkorder() {
            stubWorkorder(WorkorderStatus.APPROVED);
            when(workorderServiceRepository.findById(SERVICE_LINE_ID)).thenReturn(Optional.of(serviceLine()));

            service.startTimer(
                    TECHNICIAN_ID,
                    new WorkexecTimeTrackingService.TimerStartRequest(
                            WORKORDER_ID, SERVICE_LINE_ID, "BRAKE_JOB", null, null),
                    null);
            assertThat(savedEntry().getWorkorderServiceId()).isEqualTo(SERVICE_LINE_ID);
        }

        @Test
        void refusesAWorkorderItemThatBelongsToAnotherWorkorder() {
            stubWorkorder(WorkorderStatus.APPROVED);
            Workorder otherWorkorder = new Workorder();
            otherWorkorder.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7aff"));
            WorkorderServiceLine foreign = WorkorderServiceLine.builder()
                    .id(SERVICE_LINE_ID)
                    .workOrder(otherWorkorder)
                    .build();
            when(workorderServiceRepository.findById(SERVICE_LINE_ID)).thenReturn(Optional.of(foreign));
            WorkexecTimeTrackingService.TimerStartRequest request = new WorkexecTimeTrackingService.TimerStartRequest(
                    WORKORDER_ID, SERVICE_LINE_ID, "BRAKE_JOB", null, null);

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(WorkexecTimeTrackingService.WorkexecConflictException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void failsForAWorkorderItemThatDoesNotExist() {
            stubWorkorder(WorkorderStatus.APPROVED);
            when(workorderServiceRepository.findById(SERVICE_LINE_ID)).thenReturn(Optional.empty());
            WorkexecTimeTrackingService.TimerStartRequest request = new WorkexecTimeTrackingService.TimerStartRequest(
                    WORKORDER_ID, SERVICE_LINE_ID, "BRAKE_JOB", null, null);

            assertThatThrownBy(() -> service.startTimer(TECHNICIAN_ID, request, null))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("getActiveTimers and stopTimers")
    class ActiveAndStop {

        @Test
        void listsTimersTheActorTracksOrInitiated() {
            when(laborEntryRepository.findActiveByTechnicianOrActor(TECHNICIAN_ID))
                    .thenReturn(List.of(laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), null, "0")));

            List<WorkexecTimeTrackingService.TimerEntry> timers = service.getActiveTimers(TECHNICIAN_ID);

            assertThat(timers).hasSize(1);
            assertThat(timers.get(0).status()).isEqualTo("ACTIVE");
            assertThat(timers.get(0).durationInSeconds()).isNull();
            assertThat(timers.get(0).laborCode()).isEqualTo("BRAKE_JOB");
        }

        @Test
        void stopsEveryTimerTheActorCanReach() {
            WorkorderLaborEntry active = laborEntry(LocalDateTime.of(2026, 3, 1, 8, 0), null, "0");
            when(laborEntryRepository.findActiveByTechnicianOrActor(TECHNICIAN_ID))
                    .thenReturn(List.of(active));
            when(laborEntryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

            List<WorkexecTimeTrackingService.TimerEntry> stopped = service.stopTimers(TECHNICIAN_ID);

            assertThat(active.getEndTime()).isNotNull();
            assertThat(stopped).hasSize(1);
            assertThat(stopped.get(0).status()).isEqualTo("COMPLETED");
            assertThat(stopped.get(0).durationInSeconds()).isNotNull();
        }

        @Test
        void refusesToStopWhenNothingIsRunning() {
            when(laborEntryRepository.findActiveByTechnicianOrActor(TECHNICIAN_ID))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.stopTimers(TECHNICIAN_ID))
                    .isInstanceOf(WorkexecTimeTrackingService.WorkexecConflictException.class)
                    .hasMessageContaining("No active timer");
        }
    }
}
