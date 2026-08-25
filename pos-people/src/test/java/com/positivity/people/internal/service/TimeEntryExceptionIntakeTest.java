package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.enums.ExceptionSeverity;
import com.positivity.people.internal.enums.ExceptionStatus;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Exception intake and listing. Resolution and acknowledgement flows are covered by
 * {@code com.positivity.people.service.TimeEntryExceptionServiceTest}; this class covers
 * creation, the read side, and the audit-write failure paths that must not fail the action.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TimeEntryExceptionServiceImpl intake")
class TimeEntryExceptionIntakeTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID EXCEPTION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a01");
    private static final String EMPLOYEE_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a02";
    private static final String ACTOR = "jane.smith";

    @Mock
    private TimeEntryExceptionRepository exceptionRepository;

    @Mock
    private TimeEntryAuditRepository auditRepository;

    private TimeEntryExceptionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TimeEntryExceptionServiceImpl(
                exceptionRepository, auditRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(exceptionRepository.save(any())).thenAnswer(invocation -> {
            com.positivity.people.internal.entity.TimeEntryException entity = invocation.getArgument(0);
            if (entity.getExceptionId() == null) {
                entity.setExceptionId(EXCEPTION_ID);
            }
            return entity;
        });
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(ACTOR, "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, ACTOR));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static TimeEntryExceptionRequest request(String severity) {
        TimeEntryExceptionRequest request = new TimeEntryExceptionRequest();
        request.setEmployeeId(EMPLOYEE_ID);
        request.setExceptionCode("MISSING_PUNCH_OUT");
        request.setSeverity(severity);
        request.setTimeEntryId("TE-1");
        request.setResolutionNotes("Detected by nightly sweep");
        return request;
    }

    private static com.positivity.people.internal.entity.TimeEntryException entity(ExceptionStatus status) {
        com.positivity.people.internal.entity.TimeEntryException exception =
                new com.positivity.people.internal.entity.TimeEntryException();
        exception.setExceptionId(EXCEPTION_ID);
        exception.setEmployeeId(EMPLOYEE_ID);
        exception.setWorkDate(LocalDate.of(2026, 3, 1));
        exception.setExceptionCode("MISSING_PUNCH_OUT");
        exception.setSeverity(ExceptionSeverity.BLOCKING);
        exception.setStatus(status);
        exception.setTimeEntryId("TE-1");
        exception.setDetectedAt(NOW);
        return exception;
    }

    @Nested
    @DisplayName("createException")
    class CreateException {

        @Test
        void opensTheExceptionAndStampsTheDetectionTime() {
            TimeEntryExceptionResponse response = service.createException(request("BLOCKING"));

            ArgumentCaptor<com.positivity.people.internal.entity.TimeEntryException> saved = ArgumentCaptor.captor();
            verify(exceptionRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(ExceptionStatus.OPEN);
            assertThat(saved.getValue().getSeverity()).isEqualTo(ExceptionSeverity.BLOCKING);
            assertThat(saved.getValue().getDetectedAt()).isEqualTo(NOW);
            assertThat(saved.getValue().getExceptionCode()).isEqualTo("MISSING_PUNCH_OUT");
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("created");
            assertThat(response.getExceptionId()).isEqualTo(EXCEPTION_ID);
        }

        @Test
        void keepsACallerSuppliedDetectionTime() {
            TimeEntryExceptionRequest request = request("WARNING");
            request.setDetectedAt(OffsetDateTime.parse("2026-02-28T09:30:00Z"));

            service.createException(request);

            ArgumentCaptor<com.positivity.people.internal.entity.TimeEntryException> saved = ArgumentCaptor.captor();
            verify(exceptionRepository).save(saved.capture());
            assertThat(saved.getValue().getDetectedAt()).isEqualTo(Instant.parse("2026-02-28T09:30:00Z"));
        }

        @Test
        void downgradesAnUnrecognisedSeverityToWarningRatherThanFailing() {
            service.createException(request("CATASTROPHIC"));

            ArgumentCaptor<com.positivity.people.internal.entity.TimeEntryException> saved = ArgumentCaptor.captor();
            verify(exceptionRepository).save(saved.capture());
            assertThat(saved.getValue().getSeverity()).isEqualTo(ExceptionSeverity.WARNING);
        }

        @Test
        void leavesSeverityUnsetWhenTheRequestOmitsIt() {
            service.createException(request(null));

            ArgumentCaptor<com.positivity.people.internal.entity.TimeEntryException> saved = ArgumentCaptor.captor();
            verify(exceptionRepository).save(saved.capture());
            assertThat(saved.getValue().getSeverity()).isNull();
        }
    }

    @Nested
    @DisplayName("listByEmployee")
    class ListByEmployee {

        @Test
        void mapsTheEmployeesExceptionsToTheirDtoShape() {
            when(exceptionRepository.findByEmployeeId(EMPLOYEE_ID)).thenReturn(List.of(entity(ExceptionStatus.OPEN)));

            List<com.positivity.people.internal.dto.TimeEntryException> exceptions =
                    service.listByEmployee(EMPLOYEE_ID);

            assertThat(exceptions).hasSize(1);
            assertThat(exceptions.get(0).getExceptionId()).isEqualTo(EXCEPTION_ID);
            assertThat(exceptions.get(0).getEmployeeId()).isEqualTo(EMPLOYEE_ID);
            assertThat(exceptions.get(0).getWorkDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(exceptions.get(0).getSeverity()).isEqualTo(ExceptionSeverity.BLOCKING);
            assertThat(exceptions.get(0).getStatus()).isEqualTo(ExceptionStatus.OPEN);
            assertThat(exceptions.get(0).getTimeEntryId()).isEqualTo("TE-1");
            assertThat(exceptions.get(0).getDetectedAt()).isEqualTo(NOW);
        }

        @Test
        void readsEveryExceptionWhenNoEmployeeIsNamed() {
            when(exceptionRepository.findAll()).thenReturn(List.of(entity(ExceptionStatus.ACKNOWLEDGED)));

            assertThat(service.listByEmployee(null)).hasSize(1);
            verify(exceptionRepository).findAll();
        }
    }

    @Nested
    @DisplayName("audit resilience")
    class AuditResilience {

        @Test
        void completesTheActionEvenWhenTheAuditWriteFails() {
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(entity(ExceptionStatus.OPEN)));
            doThrow(new IllegalStateException("audit store down"))
                    .when(auditRepository)
                    .save(any(TimeEntryAudit.class));

            service.actionException(EXCEPTION_ID, ExceptionStatus.ACKNOWLEDGED, "seen", "corr-1");

            verify(exceptionRepository).save(any());
        }

        @Test
        void recordsAnEmptyTimeEntryIdOnTheAuditRowWhenTheExceptionHasNone() {
            com.positivity.people.internal.entity.TimeEntryException detached = entity(ExceptionStatus.OPEN);
            detached.setTimeEntryId(null);
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(detached));

            service.actionException(EXCEPTION_ID, ExceptionStatus.ACKNOWLEDGED, null, null);

            ArgumentCaptor<TimeEntryAudit> audit = ArgumentCaptor.forClass(TimeEntryAudit.class);
            verify(auditRepository).save(audit.capture());
            assertThat(audit.getValue().getTimeEntryId()).isEmpty();
            assertThat(audit.getValue().getAction()).isEqualTo("EXCEPTION_ACKNOWLEDGED");
            assertThat(audit.getValue().getActorId()).isEqualTo(ACTOR);
        }

        @Test
        void stillDeniesTheResolveWhenTheDenialAuditWriteFails() {
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(entity(ExceptionStatus.OPEN)));
            doThrow(new IllegalStateException("audit store down"))
                    .when(auditRepository)
                    .save(any(TimeEntryAudit.class));

            assertThatThrownBy(() -> service.resolveException(EXCEPTION_ID, Set.of(), null, null, "corr-1"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void treatsAMissingPermissionSetAsADenial() {
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(entity(ExceptionStatus.OPEN)));

            assertThatThrownBy(() -> service.resolveException(EXCEPTION_ID, null, null, null, null))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        void acceptsTheSpecificResolvePermission() {
            com.positivity.people.internal.entity.TimeEntryException open = entity(ExceptionStatus.OPEN);
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(open));

            service.resolveException(
                    EXCEPTION_ID, Set.of(PeoplePermissions.TIMEEXCEPTION_RESOLVE), "fixed", null, "corr-1");

            assertThat(open.getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
            assertThat(open.getResolvedBy()).isEqualTo(ACTOR);
            assertThat(open.getResolvedAt()).isEqualTo(NOW);
            assertThat(open.getResolutionNotes()).isEqualTo("fixed");
        }

        @Test
        void appliesAnExplicitResolutionAction() {
            com.positivity.people.internal.entity.TimeEntryException open = entity(ExceptionStatus.OPEN);
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(open));

            service.resolveException(
                    EXCEPTION_ID, Set.of(PeoplePermissions.TIMEEXCEPTION_RESOLVE), null, "WAIVED", null);

            assertThat(open.getStatus()).isEqualTo(ExceptionStatus.WAIVED);
        }

        @Test
        void fallsBackToResolvedForAnUnknownResolutionAction() {
            com.positivity.people.internal.entity.TimeEntryException open = entity(ExceptionStatus.OPEN);
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(open));

            service.resolveException(
                    EXCEPTION_ID, Set.of(PeoplePermissions.TIMEEXCEPTION_RESOLVE), null, "NOT_A_STATUS", null);

            assertThat(open.getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        }

        @Test
        void failsWhenTheExceptionDoesNotExist() {
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveException(
                            EXCEPTION_ID, Set.of(PeoplePermissions.TIMEEXCEPTION_RESOLVE), null, null, null))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> service.actionException(EXCEPTION_ID, ExceptionStatus.ACKNOWLEDGED, null, null))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void refusesToActionAnAlreadyWaivedException() {
            when(exceptionRepository.findById(EXCEPTION_ID)).thenReturn(Optional.of(entity(ExceptionStatus.WAIVED)));

            assertThatThrownBy(() -> service.actionException(EXCEPTION_ID, ExceptionStatus.ACKNOWLEDGED, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WAIVED");
        }

        @Test
        void failsWhenThereIsNoAuthenticatedActor() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> service.actionException(EXCEPTION_ID, ExceptionStatus.ACKNOWLEDGED, null, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
