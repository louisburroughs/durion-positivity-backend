package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ConflictOverride;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.entity.SchedulingConflict;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.ConflictOverrideStateException;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ConflictOverrideRepository;
import com.positivity.shopmanager.internal.repository.SchedulingConflictRepository;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * DECISION-SHOPMGMT-002's override half (CAP-326, spec D18.3): a manager accepts SOFT conflicts
 * already recorded against an appointment; HARD is never overridden and never written.
 */
@ExtendWith(MockitoExtension.class)
class ConflictOverrideServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID APPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_APPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID CONFLICT_A = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CONFLICT_B = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final String MANAGER = "jane.manager";

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private SchedulingConflictRepository schedulingConflictRepository;

    @Mock
    private ConflictOverrideRepository conflictOverrideRepository;

    private ConflictOverrideServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConflictOverrideServiceImpl(
                appointmentRepository, schedulingConflictRepository, conflictOverrideRepository, FIXED_CLOCK);
        // A holder with no scope claims: permissive by design (LocationScope.unscoped()), so the
        // guard passes and the actor resolves to the token's name.
        var token = new UsernamePasswordAuthenticationToken(
                MANAGER, null, List.of(new SimpleGrantedAuthority(ShopPermissions.CONFLICT_OVERRIDE)));
        // The gateway carries the username as a detail, which is what the helper reads (ADR-0018).
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, MANAGER));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the service interface is gated by shop:conflict:override, not the schedule-edit authorities")
    void interfaceDeclaresTheOverridePermission() throws Exception {
        var method = ConflictOverrideService.class.getMethod("execute", UUID.class, ConflictOverrideRequest.class);
        assertThat(method.isAnnotationPresent(PreAuthorize.class)).isTrue();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains(ShopPermissions.CONFLICT_OVERRIDE)
                .doesNotContain("shop:schedule:edit")
                .doesNotContain("appointments:reschedule");
    }

    @Test
    @DisplayName("SOFT conflicts of the appointment: one immutable row each, single-actor approval, rule codes echoed")
    void softConflictsAreOverriddenWithSingleActorApproval() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.of(appointment(APPT_ID)));
        when(schedulingConflictRepository.findAllById(any()))
                .thenReturn(List.of(
                        conflict(CONFLICT_B, APPT_ID, "FACILITY_NEAR_CAPACITY", ConflictSeverity.SOFT),
                        conflict(CONFLICT_A, APPT_ID, "MECHANIC_OVERTIME", ConflictSeverity.SOFT)));
        when(conflictOverrideRepository.existsByConflict_Id(any())).thenReturn(false);
        when(conflictOverrideRepository.save(any())).thenAnswer(invocation -> {
            ConflictOverride saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ConflictOverrideResponse response =
                service.execute(APPT_ID, request(List.of(CONFLICT_A, CONFLICT_B), "Customer waiting"));

        ArgumentCaptor<ConflictOverride> captor = ArgumentCaptor.forClass(ConflictOverride.class);
        verify(conflictOverrideRepository, times(2)).save(captor.capture());
        for (ConflictOverride row : captor.getAllValues()) {
            assertThat(row.getOverriddenBy()).isEqualTo(MANAGER);
            assertThat(row.getApprovedBy()).isEqualTo(MANAGER);
            assertThat(row.getApprovedAt()).isEqualTo(Instant.now(FIXED_CLOCK));
            assertThat(row.getCreatedAt()).isEqualTo(Instant.now(FIXED_CLOCK));
            assertThat(row.getOverrideReason()).isEqualTo("Customer waiting");
        }
        // Request order, not repository order.
        assertThat(response.getOverrides())
                .extracting(ConflictOverrideResponse.OverrideEntry::getConflictId)
                .containsExactly(CONFLICT_A, CONFLICT_B);
        assertThat(response.getOverrides())
                .extracting(ConflictOverrideResponse.OverrideEntry::getRuleCode)
                .containsExactly("MECHANIC_OVERTIME", "FACILITY_NEAR_CAPACITY");
        assertThat(response.getOverrides())
                .allSatisfy(entry -> assertThat(entry.getSeverity()).isEqualTo("SOFT"));
        assertThat(response.getAppointmentId()).isEqualTo(APPT_ID);
        assertThat(response.getOverriddenBy()).isEqualTo(MANAGER);
        assertThat(response.getApprovedAt()).isEqualTo(Instant.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("a HARD conflict is refused with the DECISION-002 envelope and nothing is written")
    void hardConflictIsRefusedWithTheEnvelopeAndNoRow() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.of(appointment(APPT_ID)));
        when(schedulingConflictRepository.findAllById(any()))
                .thenReturn(List.of(
                        conflict(CONFLICT_A, APPT_ID, "MECHANIC_OVERTIME", ConflictSeverity.SOFT),
                        conflict(CONFLICT_B, APPT_ID, "BAY_DOUBLE_BOOKED", ConflictSeverity.HARD)));

        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(CONFLICT_A, CONFLICT_B), "Please")))
                .isInstanceOfSatisfying(SchedulingConflictException.class, exception -> {
                    var envelope = exception.getConflictResponse();
                    assertThat(envelope.getErrorCode()).isEqualTo("SCHEDULING_CONFLICT");
                    assertThat(envelope.getConflicts()).hasSize(1);
                    assertThat(envelope.getConflicts().get(0).getCode()).isEqualTo("BAY_DOUBLE_BOOKED");
                    assertThat(envelope.getConflicts().get(0).getSeverity()).isEqualTo("HARD");
                    assertThat(envelope.getConflicts().get(0).getOverridable()).isFalse();
                });
        // All-or-nothing: the SOFT sibling is not overridden either.
        verify(conflictOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("a conflict already carrying an override is refused, and nothing is written")
    void alreadyOverriddenIsRefused() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.of(appointment(APPT_ID)));
        when(schedulingConflictRepository.findAllById(any()))
                .thenReturn(List.of(conflict(CONFLICT_A, APPT_ID, "MECHANIC_OVERTIME", ConflictSeverity.SOFT)));
        when(conflictOverrideRepository.existsByConflict_Id(CONFLICT_A)).thenReturn(true);

        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(CONFLICT_A), "Again")))
                .isInstanceOf(ConflictOverrideStateException.class);
        verify(conflictOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("a conflict recorded against another appointment is the client's error: 400, nothing written")
    void conflictOfAnotherAppointmentIsRejected() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.of(appointment(APPT_ID)));
        when(schedulingConflictRepository.findAllById(any()))
                .thenReturn(List.of(conflict(CONFLICT_A, OTHER_APPT_ID, "MECHANIC_OVERTIME", ConflictSeverity.SOFT)));

        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(CONFLICT_A), "Wrong one")))
                .isInstanceOf(ShopManagerValidationException.class)
                .hasMessageContaining(CONFLICT_A.toString());
        verify(conflictOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown conflict id is rejected the same way")
    void unknownConflictIsRejected() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.of(appointment(APPT_ID)));
        when(schedulingConflictRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(CONFLICT_A), "Ghost")))
                .isInstanceOf(ShopManagerValidationException.class);
        verify(conflictOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("a blank reason or an empty id list is rejected before any repository is touched")
    void blankReasonOrEmptyIdsRejectedEarly() {
        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(CONFLICT_A), "   ")))
                .isInstanceOf(ShopManagerValidationException.class);
        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(), "Reason")))
                .isInstanceOf(ShopManagerValidationException.class);
        verify(appointmentRepository, never()).findById(any());
        verify(conflictOverrideRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown appointment is 404")
    void unknownAppointmentIsNotFound() {
        when(appointmentRepository.findById(APPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(APPT_ID, request(List.of(CONFLICT_A), "Reason")))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    private static ConflictOverrideRequest request(List<UUID> ids, String reason) {
        return ConflictOverrideRequest.builder()
                .conflictIds(ids)
                .overrideReason(reason)
                .build();
    }

    private static Appointment appointment(UUID id) {
        return Appointment.builder().appointmentId(id).locationId(LOCATION_ID).build();
    }

    private static SchedulingConflict conflict(UUID id, UUID appointmentId, String code, ConflictSeverity severity) {
        return SchedulingConflict.builder()
                .id(id)
                .appointment(appointment(appointmentId))
                .conflictRule(ConflictRule.builder()
                        .id(UUID.nameUUIDFromBytes(code.getBytes()))
                        .code(code)
                        .severity(severity)
                        .resourceType(ConflictResourceType.BAY)
                        .messageTemplate(code)
                        .build())
                .severity(severity)
                .locationId(LOCATION_ID)
                .resourceId("bay-1")
                .attemptedStartAt(Instant.parse("2026-06-16T09:00:00Z"))
                .attemptedEndAt(Instant.parse("2026-06-16T10:00:00Z"))
                .detail(code + " fired")
                .detectedAt(Instant.parse("2026-06-15T11:00:00Z"))
                .build();
    }
}
