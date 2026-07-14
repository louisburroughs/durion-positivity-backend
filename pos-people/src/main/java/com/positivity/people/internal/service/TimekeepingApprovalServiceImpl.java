package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.ApprovalPersonDto;
import com.positivity.people.internal.dto.TimePeriodApprovalDto;
import com.positivity.people.internal.dto.TimePeriodDecisionResponse;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimekeepingEntryDto;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.entity.TimekeepingEntry;
import com.positivity.people.internal.enums.ApprovalStatus;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimePeriodRepository;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import com.positivity.people.service.TimekeepingApprovalService;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TimekeepingApprovalServiceImpl implements TimekeepingApprovalService {

    private final TimekeepingEntryRepository timekeepingEntryRepository;
    private final TimePeriodRepository timePeriodRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final EmployeeRepository employeeRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalPersonDto> listApprovalPeople() {
        List<UUID> employeeIds = timekeepingEntryRepository.findDistinctEmployeeIds();
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        List<ExtPersonReplica> persons = extPersonReplicaRepository.findAllById(employeeIds);
        java.util.Map<UUID, String> employeeNumbers = new java.util.HashMap<>();
        employeeRepository
                .findByPersonIdIn(employeeIds)
                .forEach(e -> employeeNumbers.put(e.getPersonId(), e.getEmployeeNumber()));
        return persons.stream()
                .map(p -> ApprovalPersonDto.builder()
                        .personId(p.getPersonId())
                        .displayName(buildDisplayName(p))
                        .employeeNumber(employeeNumbers.get(p.getPersonId()))
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimePeriodDto> listTimePeriods(UUID tenantId) {
        List<TimePeriod> periods = tenantId != null
                ? timePeriodRepository.findAllByTenantIdOrderByStartDateDesc(tenantId)
                : timePeriodRepository.findAll();
        return periods.stream().map(this::toTimePeriodDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimekeepingEntryDto> listTimekeepingEntries(UUID personId, UUID timePeriodId) {
        TimePeriod period = timePeriodRepository
                .findById(timePeriodId)
                .orElseThrow(() -> new EntityNotFoundException("TimePeriod not found: " + timePeriodId));
        Instant start = toInstant(period.getStartDate());
        Instant end = toInstantEndOfDay(period.getEndDate());
        List<TimekeepingEntry> entries =
                timekeepingEntryRepository
                        .findByEmployeeIdAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                                personId, start, end);
        return entries.stream().map(this::toEntryDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TimePeriodApprovalDto getTimePeriodApproval(UUID personId, UUID timePeriodId) {
        TimePeriod period = timePeriodRepository
                .findById(timePeriodId)
                .orElseThrow(() -> new EntityNotFoundException("TimePeriod not found: " + timePeriodId));
        Instant start = toInstant(period.getStartDate());
        Instant end = toInstantEndOfDay(period.getEndDate());
        List<TimekeepingEntry> entries =
                timekeepingEntryRepository
                        .findByEmployeeIdAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                                personId, start, end);

        Map<ApprovalStatus, Long> counts = entries.stream()
                .collect(Collectors.groupingBy(TimekeepingEntry::getApprovalStatus, Collectors.counting()));

        int pending = counts.getOrDefault(ApprovalStatus.PENDING_APPROVAL, 0L).intValue();
        int approved = counts.getOrDefault(ApprovalStatus.APPROVED, 0L).intValue();
        int rejected = counts.getOrDefault(ApprovalStatus.REJECTED, 0L).intValue();
        int total = entries.size();

        ApprovalStatus overall;
        if (pending > 0) {
            overall = ApprovalStatus.PENDING_APPROVAL;
        } else if (rejected > 0) {
            overall = ApprovalStatus.REJECTED;
        } else {
            overall = ApprovalStatus.APPROVED;
        }

        return TimePeriodApprovalDto.builder()
                .personId(personId)
                .timePeriodId(timePeriodId)
                .totalCount(total)
                .pendingCount(pending)
                .approvedCount(approved)
                .rejectedCount(rejected)
                .overallStatus(overall)
                .build();
    }

    @Override
    @Transactional
    public TimePeriodDecisionResponse approvePeriod(UUID timePeriodId, UUID personId) {
        TimePeriod period = timePeriodRepository
                .findById(timePeriodId)
                .orElseThrow(() -> new EntityNotFoundException("TimePeriod not found: " + timePeriodId));
        Instant start = toInstant(period.getStartDate());
        Instant end = toInstantEndOfDay(period.getEndDate());
        List<TimekeepingEntry> pending =
                timekeepingEntryRepository
                        .findByEmployeeIdAndApprovalStatusAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                                personId, ApprovalStatus.PENDING_APPROVAL, start, end);

        for (TimekeepingEntry entry : pending) {
            entry.setApprovalStatus(ApprovalStatus.APPROVED);
        }
        timekeepingEntryRepository.saveAll(pending);

        return TimePeriodDecisionResponse.builder()
                .personId(personId)
                .timePeriodId(timePeriodId)
                .processedCount(pending.size())
                .status("APPROVED")
                .build();
    }

    @Override
    @Transactional
    public TimePeriodDecisionResponse rejectPeriod(UUID timePeriodId, UUID personId, String reason) {
        TimePeriod period = timePeriodRepository
                .findById(timePeriodId)
                .orElseThrow(() -> new EntityNotFoundException("TimePeriod not found: " + timePeriodId));
        Instant start = toInstant(period.getStartDate());
        Instant end = toInstantEndOfDay(period.getEndDate());
        List<TimekeepingEntry> pending =
                timekeepingEntryRepository
                        .findByEmployeeIdAndApprovalStatusAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                                personId, ApprovalStatus.PENDING_APPROVAL, start, end);

        for (TimekeepingEntry entry : pending) {
            entry.setApprovalStatus(ApprovalStatus.REJECTED);
            entry.setCorrectionReason(reason);
        }
        timekeepingEntryRepository.saveAll(pending);

        return TimePeriodDecisionResponse.builder()
                .personId(personId)
                .timePeriodId(timePeriodId)
                .processedCount(pending.size())
                .status("REJECTED")
                .build();
    }

    private TimePeriodDto toTimePeriodDto(TimePeriod p) {
        return TimePeriodDto.builder()
                .timePeriodId(p.getTimePeriodId())
                .tenantId(p.getTenantId())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .status(p.getStatus())
                .build();
    }

    private TimekeepingEntryDto toEntryDto(TimekeepingEntry e) {
        return TimekeepingEntryDto.builder()
                .timekeepingEntryId(e.getTimekeepingEntryId())
                .employeeId(e.getEmployeeId())
                .sessionStartTime(e.getSessionStartTime())
                .sessionEndTime(e.getSessionEndTime())
                .approvalStatus(e.getApprovalStatus())
                .associatedWorkOrderId(e.getAssociatedWorkOrderId())
                .build();
    }

    private String buildDisplayName(ExtPersonReplica p) {
        if (p.getPreferredName() != null && !p.getPreferredName().isBlank()) {
            return p.getPreferredName() + " " + p.getLastName();
        }
        if (p.getFirstName() != null) {
            return p.getFirstName() + " " + p.getLastName();
        }
        return p.getPersonId().toString();
    }

    private Instant toInstant(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant toInstantEndOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
