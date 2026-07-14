package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.people.service.PeopleComplianceService;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PeopleComplianceServiceImpl implements PeopleComplianceService {

    /** Person statuses that should NOT retain an active user (ADR-0015 §4). */
    static final Set<EmployeeStatus> INACTIVE_PERSON_STATUSES =
            EnumSet.of(EmployeeStatus.SUSPENDED, EmployeeStatus.TERMINATED, EmployeeStatus.DISABLED);

    private final ExtUserLinkReplicaRepository linkReplicaRepository;
    private final EmployeeRepository employeeRepository;

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<InactivePersonActiveUserResponse> findActiveUsersForInactivePersons() {
        List<ExtUserLinkReplica> links = linkReplicaRepository.findLinksByStatusForEmployeeStatuses(
                UserLinkStatus.ACTIVE.name(), INACTIVE_PERSON_STATUSES);
        Map<UUID, Employee> employeesByPerson = employeesByPersonId(
                links.stream().map(ExtUserLinkReplica::getPersonId).toList());
        return links.stream()
                .map(link -> toInactivePersonResponse(link, employeesByPerson.get(link.getPersonId())))
                .toList();
    }

    private Map<UUID, Employee> employeesByPersonId(Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Employee> result = new HashMap<>();
        for (Employee e : employeeRepository.findByPersonIdIn(personIds)) {
            result.put(e.getPersonId(), e);
        }
        return result;
    }

    private InactivePersonActiveUserResponse toInactivePersonResponse(ExtUserLinkReplica link, Employee employee) {
        return InactivePersonActiveUserResponse.builder()
                .linkId(link.getLinkId())
                .username(link.getUsername())
                .personId(link.getPersonId())
                .personStatus(employee != null ? employee.getStatus() : null)
                .personStatusEffectiveAt(employee != null ? employee.getStatusEffectiveAt() : null)
                .build();
    }
}
