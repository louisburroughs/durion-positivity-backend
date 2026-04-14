package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.TimeEntryService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link TimeEntryService} responsible for processing batch decisions
 * on employee time entries.
 * <p>
 * This service handles the approval and rejection workflows, ensuring that:
 * <ul>
 * <li>Only entries in a {@code PENDING_APPROVAL} or {@code SUBMITTED} state can be
 * processed.</li>
 * <li>The requesting user has the appropriate permissions (e.g., 'admin' or targeted
 * action rights).</li>
 * <li>A comprehensive audit trail is generated for every decision attempt, including
 * failures due to permission denials or invalid state transitions.</li>
 * </ul>
 * <p>
 * Security context and header-based permissions are evaluated to authorize state
 * modifications before committing changes to the repository.
 */
@RequiredArgsConstructor
@Service
public class TimeEntryServiceImpl implements TimeEntryService {

    private static final String ENTRY_NOT_PENDING_CODE = "ENTRY_NOT_PENDING";

    private static final String ENTRY_NOT_PENDING_MSG = "Time entry not in pending/submitted state";

    private static final String NOT_FOUND_CODE = "NOT_FOUND";

    private static final String NOT_FOUND_MSG = "Time entry not found";

    private static final String FORBIDDEN_CODE = "FORBIDDEN";

    private final Clock clock;

    private final TimeEntryRepository repository;

    private final TimeEntryAuditRepository auditRepository;

    private void recordAudit(String id, String action, String actorId, String correlationId, String details) {

        TimeEntryAudit audit = new TimeEntryAudit();
        audit.setTimeEntryId(id);
        audit.setAction(action);
        audit.setActorId(actorId);
        audit.setCorrelationId(correlationId);
        audit.setDetails(details);
        audit.setTimestamp(clock.instant());
        auditRepository.save(audit);
    }

    private boolean isAllowedToProcess(java.util.Set<String> permissions, String action) {
        return permissions != null
                && (permissions.contains("admin") || permissions.contains("people:timeEntry:" + action));
    }

    private boolean isPendingOrSubmitted(com.positivity.people.internal.enums.TimeEntryStatus status) {
        return status != null
                && (status == com.positivity.people.internal.enums.TimeEntryStatus.SUBMITTED
                        || status == com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL);
    }

    private Map<String, TimeEntry> fetchTimeEntriesById(List<String> timeEntryIds) {
        List<UUID> uuidIds = timeEntryIds.stream().map(UUID::fromString).toList();
        List<TimeEntry> entries = repository.findByTimeEntryIdIn(uuidIds);
        return entries.stream().collect(Collectors.toMap(e -> e.getTimeEntryId().toString(), e -> e));
    }

    /**
     * Approves a batch of time entries based on their IDs and the provided security
     * context.
     * <p>
     * Applies the 'approved' state if the entry is currently pending/submitted and the
     * requester has the necessary approval permissions. If permissions are missing or the
     * entry is in an invalid state, the decision is rejected and an audit is recorded.
     * @param timeEntryIds The list of time entry IDs to approve.
     * @param userIdHeader The user ID extracted from request headers, used as a fallback
     * if not in security context.
     * @param permissionsHeader A comma-separated list of permissions from headers,
     * appended to context authorities.
     * @param correlationId A unique identifier for tracing the operation in audit logs.
     * @return A list of {@link TimeEntryDecisionResult} detailing the success or failure
     * of each approval attempt.
     */
    @Override
    @Transactional
    @NonNull
    public List<TimeEntryDecisionResult> approveEntries(List<String> timeEntryIds, String correlationId) {
        if (timeEntryIds == null || timeEntryIds.isEmpty()) {
            return new ArrayList<>();
        }

        String approverUserId = "system";
        java.util.Set<String> permissions = new java.util.HashSet<>();
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            approverUserId = auth.getName();
            permissions = auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
        }

        String finalApproverUserId = approverUserId;
        java.util.Set<String> finalPermissions = permissions;

        String auditActorId = SecurityContextHelper.getCurrentUsername().orElse("system");

        Map<String, TimeEntry> byId = fetchTimeEntriesById(timeEntryIds);

        return timeEntryIds.stream()
                .map(id -> processApproval(
                        id, byId.get(id), finalApproverUserId, finalPermissions, correlationId, auditActorId))
                .toList();
    }

    private TimeEntryDecisionResult processApproval(
            String id,
            TimeEntry e,
            String approverUserId,
            java.util.Set<String> permissions,
            String correlationId,
            String auditActorId) {
        if (e == null) {
            recordAudit(
                    id,
                    "APPROVE_ATTEMPT_NOT_FOUND",
                    auditActorId,
                    correlationId,
                    "Time entry not found during approve attempt");
            return new TimeEntryDecisionResult(id, false, NOT_FOUND_CODE, NOT_FOUND_MSG);
        }

        if (!isPendingOrSubmitted(e.getStatus())) {
            return new TimeEntryDecisionResult(id, false, ENTRY_NOT_PENDING_CODE, ENTRY_NOT_PENDING_MSG);
        }

        if (!isAllowedToProcess(permissions, "approve")) {
            recordAudit(id, "APPROVE_FORBIDDEN", auditActorId, correlationId, "Permission denied");
            return new TimeEntryDecisionResult(id, false, FORBIDDEN_CODE, "Approver lacks approve permission");
        }

        // perform approval
        e.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.APPROVED);
        e.setApprovedBy(approverUserId);
        e.setApprovedAt(Instant.now(clock));
        repository.save(e);

        recordAudit(id, "APPROVED", auditActorId, correlationId, "Approved via batch API");
        return new TimeEntryDecisionResult(id, true, null, null);
    }

    /**
     * Rejects a batch of time entries with specified reasons based on their IDs.
     * <p>
     * Applies the 'rejected' state to entries if they are pending/submitted and the
     * requester holds required rejection permissions. The given mapped reasons are stored
     * along with a rejection audit.
     * @param timeEntryIds The list of time entry IDs to reject.
     * @param rejectorUserId The ID of the user performing the rejection.
     * @param rejectionReasons A map associating each time entry ID with its specific
     * reason for rejection.
     * @param permissions A set of granted permissions used to authorize the rejection
     * action.
     * @param correlationId A unique identifier for tracing the operation in audit logs.
     * @return A list of {@link TimeEntryDecisionResult} detailing the success or failure
     * of each rejection attempt.
     */
    @Override
    @Transactional
    @NonNull
    public List<TimeEntryDecisionResult> rejectEntries(
            List<String> timeEntryIds, Map<String, String> rejectionReasons, String correlationId) {
        if (timeEntryIds == null || timeEntryIds.isEmpty()) {
            return new ArrayList<>();
        }

        for (String id : timeEntryIds) {
            String reason = rejectionReasons.get(id);
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("rejectionReason is required for all decisions");
            }
        }

        String rejectorUserId = "system";
        java.util.Set<String> permissions = new java.util.HashSet<>();
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            rejectorUserId = auth.getName();
            permissions = auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
        }

        String finalRejectorUserId = rejectorUserId;
        java.util.Set<String> finalPermissions = permissions;

        String auditActorId = SecurityContextHelper.getCurrentUsername().orElse("system");

        Map<String, TimeEntry> byId = fetchTimeEntriesById(timeEntryIds);

        return timeEntryIds.stream()
                .map(id -> processRejection(
                        id,
                        byId.get(id),
                        finalRejectorUserId,
                        rejectionReasons.get(id),
                        finalPermissions,
                        correlationId,
                        auditActorId))
                .toList();
    }

    private TimeEntryDecisionResult processRejection(
            String id,
            TimeEntry e,
            String rejectorUserId,
            String rejectionReason,
            java.util.Set<String> permissions,
            String correlationId,
            String auditActorId) {
        if (e == null) {
            recordAudit(
                    id,
                    "REJECT_ATTEMPT_NOT_FOUND",
                    auditActorId,
                    correlationId,
                    "Time entry not found during reject attempt");
            return new TimeEntryDecisionResult(id, false, NOT_FOUND_CODE, NOT_FOUND_MSG);
        }

        if (!isPendingOrSubmitted(e.getStatus())) {
            return new TimeEntryDecisionResult(id, false, ENTRY_NOT_PENDING_CODE, ENTRY_NOT_PENDING_MSG);
        }

        if (!isAllowedToProcess(permissions, "reject")) {
            recordAudit(id, "REJECT_FORBIDDEN", auditActorId, correlationId, "Permission denied");
            return new TimeEntryDecisionResult(id, false, FORBIDDEN_CODE, "Rejector lacks reject permission");
        }

        // perform rejection
        e.setStatus(com.positivity.people.internal.enums.TimeEntryStatus.REJECTED);
        e.setRejectedBy(rejectorUserId);
        e.setRejectedAt(Instant.now(clock));
        e.setRejectionReason(rejectionReason != null ? rejectionReason : "");
        repository.save(e);

        recordAudit(
                id,
                "REJECTED",
                auditActorId,
                correlationId,
                "Rejected via batch API. Reason: " + (rejectionReason != null ? rejectionReason : "N/A"));
        return new TimeEntryDecisionResult(id, true, null, null);
    }
}
