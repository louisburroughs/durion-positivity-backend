package com.positivity.people.service;

import com.positivity.people.dto.TimeEntryDecisionResult;
import com.positivity.people.entity.TimeEntry;
import com.positivity.people.entity.TimeEntryAudit;
import com.positivity.people.repository.TimeEntryRepository;
import com.positivity.people.repository.TimeEntryAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TimeEntryService {

    private final TimeEntryRepository repository;
    private final TimeEntryAuditRepository auditRepository;

    public TimeEntryService(TimeEntryRepository repository, TimeEntryAuditRepository auditRepository) {
        this.repository = repository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public List<TimeEntryDecisionResult> approveEntries(List<String> timeEntryIds, String approverUserId,
            java.util.Set<String> permissions, String correlationId) {
        List<TimeEntryDecisionResult> results = new ArrayList<>();
        if (timeEntryIds == null || timeEntryIds.isEmpty())
            return results;

        List<TimeEntry> entries = repository.findByTimeEntryIdIn(timeEntryIds);
        Map<String, TimeEntry> byId = entries.stream().collect(Collectors.toMap(TimeEntry::getTimeEntryId, e -> e));

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

            com.positivity.people.model.TimeEntryStatus status = e.getStatus();
            if (status == null) {
                results.add(new TimeEntryDecisionResult(id, false, "ENTRY_NOT_PENDING",
                        "Time entry not in pending/submitted state"));
                continue;
            }
            // Accept submitted or pending approval states
            if (status != com.positivity.people.model.TimeEntryStatus.SUBMITTED
                    && status != com.positivity.people.model.TimeEntryStatus.PENDING_APPROVAL) {
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
            e.setStatus(com.positivity.people.model.TimeEntryStatus.APPROVED);
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
}
