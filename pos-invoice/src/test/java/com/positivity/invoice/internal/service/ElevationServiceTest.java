package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.client.ManagerApprovalClient;
import com.positivity.invoice.internal.dto.ElevateResponse;
import com.positivity.invoice.internal.entity.ExtEmployeeReplica;
import com.positivity.invoice.internal.exception.ElevationDeniedException;
import com.positivity.invoice.internal.repository.ExtEmployeeReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ElevationService} — the employee-number manager-approval flow:
 * a service advisor names an absent manager by employee number, the manager is resolved
 * through the {@code ext_people_employee} replica, their {@code invoice:finalize:override}
 * authority is verified against pos-security, and a token scoped to the invoice is minted.
 *
 * <p>The authority half only works because pos-security's baseline seed grants
 * {@code invoice:finalize:override} to the manager roles (#1374);
 * {@code RolePermissionSeedIT} in pos-security-service pins that end to end against the
 * real resolution path. Here the client is mocked to cover this module's orchestration
 * and its deny paths.
 */
class ElevationServiceTest {

    private static final String EMPLOYEE_NUMBER = "EMP-042";
    private static final UUID MANAGER_PERSON_ID = UUID.fromString("01990010-0000-7000-8000-00000000cafe");
    private static final UUID INVOICE_ID = UUID.fromString("01990010-0000-7000-8000-00000000beef");
    private static final String TOKEN_SECRET = "test-elevation-secret-please-rotate";

    private ManagerApprovalClient managerApprovalClient;
    private ExtEmployeeReplicaRepository extEmployeeReplicaRepository;
    private ElevationTokenService tokenService;
    private ElevationService elevationService;

    @BeforeEach
    void setUp() {
        managerApprovalClient = mock(ManagerApprovalClient.class);
        extEmployeeReplicaRepository = mock(ExtEmployeeReplicaRepository.class);
        tokenService = new ElevationTokenService(
                Clock.fixed(Instant.parse("2026-08-19T12:00:00Z"), ZoneOffset.UTC), TOKEN_SECRET, 300);
        elevationService = new ElevationService(managerApprovalClient, extEmployeeReplicaRepository, tokenService);
    }

    @Test
    void elevate_mintsInvoiceScopedToken_whenManagerActiveAndHoldsFinalizeOverride() {
        when(extEmployeeReplicaRepository.findFirstByEmployeeNumberIgnoreCase(EMPLOYEE_NUMBER))
                .thenReturn(Optional.of(activeManager()));
        when(managerApprovalClient.personHoldsFinalizeOverride(MANAGER_PERSON_ID))
                .thenReturn(true);

        ElevateResponse response = elevationService.elevate(EMPLOYEE_NUMBER, INVOICE_ID);

        // The minted token must verify against the invoice it was requested for and
        // carry the approving manager, i.e. it is accepted by the finalize call.
        assertThat(tokenService.verify(response.getElevationToken(), INVOICE_ID))
                .contains(MANAGER_PERSON_ID);
        assertThat(response.getExpiresAt()).isAfter(Instant.parse("2026-08-19T12:00:00Z"));
        verify(managerApprovalClient).personHoldsFinalizeOverride(MANAGER_PERSON_ID);
    }

    @Test
    void elevate_denies_whenNamedManagerLacksFinalizeOverride() {
        // The pre-#1374 production state: the manager exists and is active, but no role
        // grants invoice:finalize:override, so the person-decision comes back DENY.
        when(extEmployeeReplicaRepository.findFirstByEmployeeNumberIgnoreCase(EMPLOYEE_NUMBER))
                .thenReturn(Optional.of(activeManager()));
        when(managerApprovalClient.personHoldsFinalizeOverride(MANAGER_PERSON_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> elevationService.elevate(EMPLOYEE_NUMBER, INVOICE_ID))
                .isInstanceOf(ElevationDeniedException.class)
                .hasMessage("Manager approval denied");
    }

    @Test
    void elevate_denies_whenEmployeeNumberUnknown() {
        when(extEmployeeReplicaRepository.findFirstByEmployeeNumberIgnoreCase(EMPLOYEE_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> elevationService.elevate(EMPLOYEE_NUMBER, INVOICE_ID))
                .isInstanceOf(ElevationDeniedException.class);
        verifyNoInteractions(managerApprovalClient);
    }

    @Test
    void elevate_denies_whenEmployeeInactive_withoutConsultingSecurity() {
        ExtEmployeeReplica inactive = activeManager();
        inactive.setStatus("TERMINATED");
        when(extEmployeeReplicaRepository.findFirstByEmployeeNumberIgnoreCase(EMPLOYEE_NUMBER))
                .thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> elevationService.elevate(EMPLOYEE_NUMBER, INVOICE_ID))
                .isInstanceOf(ElevationDeniedException.class);
        verifyNoInteractions(managerApprovalClient);
    }

    private static ExtEmployeeReplica activeManager() {
        return ExtEmployeeReplica.builder()
                .employeeId(UUID.fromString("01990010-0000-7000-8000-00000000f00d"))
                .personId(MANAGER_PERSON_ID)
                .employeeNumber(EMPLOYEE_NUMBER)
                .status("ACTIVE")
                .aggregateVersion(1L)
                .build();
    }
}
