package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.people.internal.service.PeopleComplianceServiceImpl;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PeopleComplianceServiceTest {

    private static final UUID LINK_ID = UUID.fromString("01960012-0000-7000-8000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");
    private static final Instant STATUS_EFFECTIVE_AT = Instant.parse("2026-01-15T09:30:00Z");

    private ExtUserLinkReplicaRepository linkReplicaRepository;
    private EmployeeRepository employeeRepository;
    private PeopleComplianceService service;

    @BeforeEach
    void setUp() {
        linkReplicaRepository = mock(ExtUserLinkReplicaRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        service = new PeopleComplianceServiceImpl(linkReplicaRepository, employeeRepository);
    }

    private ExtUserLinkReplica link() {
        return ExtUserLinkReplica.builder()
                .linkId(LINK_ID)
                .personId(PERSON_ID)
                .username("marcus.webb")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("Offending link is mapped with the employee's status and effective timestamp")
    void mapsLinkWithEmployeeStatus() {
        when(linkReplicaRepository.findLinksByStatusForEmployeeStatuses(
                        eq("ACTIVE"),
                        eq(EnumSet.of(EmployeeStatus.SUSPENDED, EmployeeStatus.TERMINATED, EmployeeStatus.DISABLED))))
                .thenReturn(List.of(link()));
        when(employeeRepository.findByPersonIdIn(anyCollection()))
                .thenReturn(List.of(Employee.builder()
                        .id(UUID.fromString("01960013-0000-7000-8000-000000000001"))
                        .personId(PERSON_ID)
                        .status(EmployeeStatus.TERMINATED)
                        .statusEffectiveAt(STATUS_EFFECTIVE_AT)
                        .build()));

        List<InactivePersonActiveUserResponse> result = service.findActiveUsersForInactivePersons();

        assertThat(result).hasSize(1);
        InactivePersonActiveUserResponse row = result.getFirst();
        assertThat(row.getLinkId()).isEqualTo(LINK_ID);
        assertThat(row.getUsername()).isEqualTo("marcus.webb");
        assertThat(row.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(row.getPersonStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(row.getPersonStatusEffectiveAt()).isEqualTo(STATUS_EFFECTIVE_AT);
    }

    @Test
    @DisplayName("Empty report when no link violates the policy; employee lookup is skipped")
    void emptyReportSkipsEmployeeLookup() {
        when(linkReplicaRepository.findLinksByStatusForEmployeeStatuses(any(), anyCollection()))
                .thenReturn(List.of());

        assertThat(service.findActiveUsersForInactivePersons()).isEmpty();
        verifyNoInteractions(employeeRepository);
    }

    @Test
    @DisplayName("A link whose employee row vanished between query and mapping still reports with null status")
    void missingEmployeeRowYieldsNullStatus() {
        when(linkReplicaRepository.findLinksByStatusForEmployeeStatuses(any(), anyCollection()))
                .thenReturn(List.of(link()));
        when(employeeRepository.findByPersonIdIn(anyCollection())).thenReturn(List.of());

        List<InactivePersonActiveUserResponse> result = service.findActiveUsersForInactivePersons();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPersonStatus()).isNull();
        assertThat(result.getFirst().getPersonStatusEffectiveAt()).isNull();
        verify(employeeRepository).findByPersonIdIn(anyCollection());
    }
}
