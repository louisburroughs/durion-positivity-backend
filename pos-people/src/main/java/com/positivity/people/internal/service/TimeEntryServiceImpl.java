package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.TimeEntryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeEntryServiceImpl implements TimeEntryService {

    private final TimeEntryRepository repository;
    private final TimeEntryAuditRepository auditRepository;

    public TimeEntryServiceImpl(TimeEntryRepository repository, TimeEntryAuditRepository auditRepository) {
        this.repository = repository;
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional
    @NonNull
    public List<TimeEntryDecisionResult> approveEntries(List<String> timeEntryIds, String approverUserId,
            java.util.Set<String> permissions, String correlationId) {
        List<TimeEntryDecisionResult> results = new ArrayList<>();
        if (timeEntryIds == null || timeEntryIds.isEmpty())
            return results;

        // Convert String IDs to UUIDs for repository query
        List<UUID> uuidIds = timeEntryIds.stream()
                .map(UUID::fromString)
                .toList();
        List<TimeEntry> entries = repository.findByTimeEntryIdIn(uuidIds);
        // Map by String representation for lookup
        Map<String, TimeEntry> byId = entries.stream()
                .collect(Collectors.toMap(e -> e.getTimeEntryId().toString(), e -> e));

        for (String id : timeEntryIds) {
            TimeEntry e = byId.get(id);
            if (e == null) {
                results.add(new TimeEntryDecisionResult(id, false, "NOT_FOUND", "Time entry not found"));
                try {
                    TimeEntryAudit audit = new TimeEntryAudit();
                    audit.setTimeEntryId(id);
                    audit.setAction("APPROVE_ATTEMPT_NOT_FOUND");
                    audit.setActorId(approverUserId);
                    audit.setCorrelationId(correlationId);
                    audit.setDetails("Time entry not found during approve attempt");
                    auditRepository.save(audit);
                } catch (Exception ignore) {
                }
                continue;
            }

            com.positivity.people.internal.enums.TimeEntryStatus status = e.getStatus();
            if (status == null) {
                results.add(new TimeEntryDecisionResult(id, false, "ENTRY_NOT_PENDING",
                        "Time entry not in pending/submitted state"));
                continue;
            }
            // Accept submitted or pending approval states
            if (status != com.positivity.people.internal.enums.TimeEntryStatus.SUBMITTED
                    && status != com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL) {
                results.add(new TimeEntryDecisionResult(id, false, "ENTRY_NOT_PENDING",
                        "Time entry not in pending/submitted state"));
                continue;
            }

            // permission check
            boolean allowed = false;
            if (permissions != null) {
                if (permissions.contains("people:timeEntry:approve") || permissions.contains("admin")) {
                    allowed = true;
                }
            }
            if (!allowed) {
                results.add(new TimeEntryDecisionResult(id, false, "FORBIDDEN", "Approver lacks approve permission"));
                try {
                    TimeEntryAudit audit = new TimeEntryAudit();
                    audit.setTimeEntryId(id);
                    audit.setAction("APPROVE_FORBIDDEN");
                    audit.setActorId(approverUserId);
                    audit.setCorrelationId(correlationId);
                    audit.setDetails("Permission denied");
                    auditRepository.save(audit);
                } catch (Exception ignore) {
                }
                continue;
            }

            // perform approval
            e.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.APPROVED);
            e.setApprovedBy(approverUserId);
            e.setApprovedAt(Instant.now());
            repository.save(e);

            // record audit success
            try {
                TimeEntryAudit audit = new TimeEntryAudit();
                audit.setTimeEntryId(id);
                audit.setAction("APPROVED");
                audit.setActorId(approverUserId);
                audit.setCorrelationId(correlationId);
                audit.setDetails("Approved via batch API");
                auditRepository.save(audit);
            } catch (Exception ignore) {
            }

            results.add(new TimeEntryDecisionResult(id, true, null, null));
        }

        return results;
    }

    @Override
    @Transactional
    @NonNull
    public List<TimeEntryDecisionResult> rejectEntries(List<String> timeEntryIds, String rejectorUserId,
            Map<String, String> rejectionReasons, java.util.Set<String> permissions, String correlationId) {
        List<TimeEntryDecisionResult> results = new ArrayList<>();
        if (timeEntryIds == null || timeEntryIds.isEmpty())
            return results;

        // Convert String IDs to UUIDs for repository query
        List<UUID> uuidIds = timeEntryIds.stream()
                .map(UUID::fromString)
                .toList();
        List<TimeEntry> entries = repository.findByTimeEntryIdIn(uuidIds);
        // Map by String representation for lookup
        Map<String, TimeEntry> byId = entries.stream()
                .collect(Collectors.toMap(e -> e.getTimeEntryId().toString(), e -> e));

        for (String id : timeEntryIds) {
            TimeEntry e = byId.get(id);
            if (e == null) {
                results.add(new TimeEntryDecisionResult(id, false, "NOT_FOUND", "Time entry not found"));
                try {
                    TimeEntryAudit audit = new TimeEntryAudit();
                    audit.setTimeEntryId(id);
                    audit.setAction("REJECT_ATTEMPT_NOT_FOUND");
                    audit.setActorId(rejectorUserId);
                    audit.setCorrelationId(correlationId);
                    audit.setDetails("Time entry not found during reject attempt");
                    auditRepository.save(audit);
                } catch (Exception ignore) {
                }
                continue;
            }

            com.positivity.people.internal.enums.TimeEntryStatus status = e.getStatus();
            if (status == null) {
                results.add(new TimeEntryDecisionResult(id, false, "ENTRY_NOT_PENDING",
                        "Time entry not in pending/submitted state"));
                continue;
            }
            // Accept submitted or pending approval states
            if (status != com.positivity.people.internal.enums.TimeEntryStatus.SUBMITTED
                    && status != com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL) {
                results.add(new TimeEntryDecisionResult(id, false, "ENTRY_NOT_PENDING",
                        "Time entry not in pending/submitted state"));
                continue;
            }

            // permission check
            boolean allowed = false;
            if (permissions != null) {
                if (permissions.contains("people:timeEntry:reject") || permissions.contains("admin")) {
                    allowed = true;
                }
            }
            if (!allowed) {
                results.add(new TimeEntryDecisionResult(id, false, "FORBIDDEN", "Rejector lacks reject permission"));
                try {
                    TimeEntryAudit audit = new TimeEntryAudit();
                    audit.setTimeEntryId(id);
                    audit.setAction("REJECT_FORBIDDEN");
                    audit.setActorId(rejectorUserId);
                    audit.setCorrelationId(correlationId);
                    audit.setDetails("Permission denied");
                    auditRepository.save(audit);
                } catch (Exception ignore) {
                }
                continue;
            }

            // perform rejection
            e.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.REJECTED);
            e.setRejectedBy(rejectorUserId);
            e.setRejectedAt(Instant.now());
            String rejectionReason = rejectionReasons.get(id);
            e.setRejectionReason(rejectionReason != null ? rejectionReason : "");
            repository.save(e);

            // record audit success
            try {
                TimeEntryAudit audit = new TimeEntryAudit();
                audit.setTimeEntryId(id);
                audit.setAction("REJECTED");
                audit.setActorId(rejectorUserId);
                audit.setCorrelationId(correlationId);
                audit.setDetails(
                        "Rejected via batch API. Reason: " + (rejectionReason != null ? rejectionReason : "N/A"));
                auditRepository.save(audit);
            } catch (Exception ignore) {
            }

            results.add(new TimeEntryDecisionResult(id, true, null, null));
        }

        return results;
    }
}
