package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.workorder.internal.domain.TimeEntryApprovedEvent;
import com.positivity.workorder.internal.domain.TimeEntryRejectedEvent;
import com.positivity.workorder.internal.dto.RejectTimeEntryRequest;
import com.positivity.workorder.internal.entity.TimeEntry;
import com.positivity.workorder.internal.enums.TimeEntryStatus;
import com.positivity.workorder.internal.exception.TimeEntryNotFoundException;
import com.positivity.workorder.internal.exception.TimeEntryStateException;
import com.positivity.workorder.internal.repository.TimeEntryRepository;
import com.positivity.workorder.internal.service.TimeEntryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.positivity.security.common.GatewaySecurityConstants;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimeEntryServiceImpl Unit Tests")
class TimeEntryServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    private static final UUID TIME_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORK_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUpSecurityContext() {
        var context = SecurityContextHolder.createEmptyContext();
        var authentication = new UsernamePasswordAuthenticationToken(
                "time-entry-test-user",
                "N/A",
                List.of(new SimpleGrantedAuthority("workorder:workorder:approve")));
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, "time-entry-test-user",
                GatewaySecurityConstants.DETAIL_USER_ID, PERSON_ID));
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock
    private TimeEntryRepository timeEntryRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private TimeEntryServiceImpl timeEntryService;

    private TimeEntry submittedEntry() {
        return TimeEntry.builder()
                .timeEntryId(TIME_ENTRY_ID).personId(PERSON_ID).workOrderId(WORK_ORDER_ID)
                .startAt(Instant.now(TEST_CLOCK).minusSeconds(3600)).endAt(Instant.now(TEST_CLOCK))
                .status(TimeEntryStatus.SUBMITTED).build();
    }

    private TimeEntry approvedEntry() {
        TimeEntry e = submittedEntry();
        e.setStatus(TimeEntryStatus.APPROVED);
        return e;
    }

    @Test
    @DisplayName("AC1: approveEntry SUBMITTED returns APPROVED with decision fields set")
    void approveEntry_succeeds() {
        when(timeEntryRepository.findById(TIME_ENTRY_ID)).thenReturn(Optional.of(submittedEntry()));
        when(timeEntryRepository.save(any(TimeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        TimeEntry result = timeEntryService.approveTimeEntry(TIME_ENTRY_ID);
        assertThat(result.getStatus()).isEqualTo(TimeEntryStatus.APPROVED);
        assertThat(result.getDecisionByUserId()).isNotNull();
        assertThat(result.getDecisionAtUtc()).isNotNull();
        verify(eventPublisher).publishEvent(any(TimeEntryApprovedEvent.class));
    }

    @Test
    @DisplayName("AC4: approveEntry non-existent ID throws TimeEntryNotFoundException")
    void approveEntry_notFound_throws() {
        when(timeEntryRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(
                () -> timeEntryService.approveTimeEntry(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isInstanceOf(TimeEntryNotFoundException.class);
    }

    @Test
    @DisplayName("AC3: approveEntry already-APPROVED throws TimeEntryStateException")
    void approveEntry_alreadyApproved_throws() {
        when(timeEntryRepository.findById(TIME_ENTRY_ID)).thenReturn(Optional.of(approvedEntry()));
        assertThatThrownBy(() -> timeEntryService.approveTimeEntry(TIME_ENTRY_ID))
                .isInstanceOf(TimeEntryStateException.class)
                .hasMessageContaining("not in SUBMITTED state");
    }

    @Test
    @DisplayName("AC2: rejectEntry SUBMITTED returns REJECTED with reason set")
    void rejectEntry_succeeds() {
        when(timeEntryRepository.findById(TIME_ENTRY_ID)).thenReturn(Optional.of(submittedEntry()));
        when(timeEntryRepository.save(any(TimeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        RejectTimeEntryRequest request = new RejectTimeEntryRequest("Hours do not match timesheet record");
        TimeEntry result = timeEntryService.rejectTimeEntry(TIME_ENTRY_ID, request);
        assertThat(result.getStatus()).isEqualTo(TimeEntryStatus.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("Hours do not match timesheet record");
        assertThat(result.getDecisionByUserId()).isNotNull();
        verify(eventPublisher).publishEvent(any(TimeEntryRejectedEvent.class));
    }

    @Test
    @DisplayName("AC4 (reject): rejectEntry non-existent ID throws TimeEntryNotFoundException")
    void rejectEntry_notFound_throws() {
        when(timeEntryRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        assertThatThrownBy(
                () -> timeEntryService.rejectTimeEntry(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new RejectTimeEntryRequest("some reason")))
                .isInstanceOf(TimeEntryNotFoundException.class);
    }

    @Test
    @DisplayName("AC3 (reject): rejectEntry already-REJECTED throws TimeEntryStateException")
    void rejectEntry_alreadyRejected_throws() {
        TimeEntry rejectedEntry = submittedEntry();
        rejectedEntry.setStatus(TimeEntryStatus.REJECTED);
        when(timeEntryRepository.findById(TIME_ENTRY_ID)).thenReturn(Optional.of(rejectedEntry));
        assertThatThrownBy(() -> timeEntryService.rejectTimeEntry(TIME_ENTRY_ID,
                new RejectTimeEntryRequest("duplicate")))
                .isInstanceOf(TimeEntryStateException.class)
                .hasMessageContaining("not in SUBMITTED state");
    }

    @Test
    @DisplayName("AC2 (additional): rejectEntry valid reason returns REJECTED entry")
    void rejectEntry_withValidReason_setsApprovalFields() {
        when(timeEntryRepository.findById(TIME_ENTRY_ID)).thenReturn(Optional.of(submittedEntry()));
        when(timeEntryRepository.save(any(TimeEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        RejectTimeEntryRequest request = new RejectTimeEntryRequest("Timesheet discrepancy");
        TimeEntry result = timeEntryService.rejectTimeEntry(TIME_ENTRY_ID, request);
        assertThat(result.getStatus()).isEqualTo(TimeEntryStatus.REJECTED);
        assertThat(result.getRejectionReason()).isNotBlank();
        assertThat(result.getDecisionAtUtc()).isNotNull();
    }
}
