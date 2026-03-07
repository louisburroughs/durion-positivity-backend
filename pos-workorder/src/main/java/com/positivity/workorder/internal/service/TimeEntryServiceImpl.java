package com.positivity.workorder.internal.service;

import java.time.Clock;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.domain.TimeEntryApprovedEvent;
import com.positivity.workorder.internal.domain.TimeEntryRejectedEvent;
import com.positivity.workorder.internal.dto.RejectTimeEntryRequest;
import com.positivity.workorder.internal.entity.TimeEntry;
import com.positivity.workorder.internal.enums.TimeEntryStatus;
import com.positivity.workorder.internal.exception.TimeEntryNotFoundException;
import com.positivity.workorder.internal.exception.TimeEntryStateException;
import com.positivity.workorder.internal.repository.TimeEntryRepository;
import com.positivity.workorder.service.TimeEntryService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Service implementation for approving and rejecting submitted time entries
 * (Story #66).
 * Actor fields are sourced from SecurityContext per ADR-0018.
 */
@Service
@RequiredArgsConstructor
public class TimeEntryServiceImpl implements TimeEntryService {
    private final Clock clock;


    private final TimeEntryRepository timeEntryRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public @NonNull TimeEntry approveTimeEntry(@NonNull UUID timeEntryId) {
        TimeEntry entry = timeEntryRepository.findById(timeEntryId)
                .orElseThrow(() -> new TimeEntryNotFoundException(timeEntryId));
        if (entry.getStatus() != TimeEntryStatus.SUBMITTED) {
            throw new TimeEntryStateException("TimeEntry " + timeEntryId + " is not in SUBMITTED state");
        }
        String actor = SecurityContextHelper.getCurrentUsername().orElse("system");
        entry.setStatus(TimeEntryStatus.APPROVED);
        entry.setDecisionByUserId(actor);
        entry.setDecisionAtUtc(Instant.now(clock));
        entry = timeEntryRepository.save(entry);
        eventPublisher.publishEvent(new TimeEntryApprovedEvent(
                entry.getTimeEntryId(), entry.getWorkOrderId(), entry.getDecisionByUserId(), entry.getDecisionAtUtc()));
        return entry;
    }

    @Override
    @Transactional
    public @NonNull TimeEntry rejectTimeEntry(@NonNull UUID timeEntryId, @NonNull RejectTimeEntryRequest request) {
        TimeEntry entry = timeEntryRepository.findById(timeEntryId)
                .orElseThrow(() -> new TimeEntryNotFoundException(timeEntryId));
        if (entry.getStatus() != TimeEntryStatus.SUBMITTED) {
            throw new TimeEntryStateException("TimeEntry " + timeEntryId + " is not in SUBMITTED state");
        }
        String actor = SecurityContextHelper.getCurrentUsername().orElse("system");
        entry.setStatus(TimeEntryStatus.REJECTED);
        entry.setRejectionReason(request.getRejectionReason());
        entry.setDecisionByUserId(actor);
        entry.setDecisionAtUtc(Instant.now(clock));
        entry = timeEntryRepository.save(entry);
        eventPublisher.publishEvent(new TimeEntryRejectedEvent(
                entry.getTimeEntryId(), entry.getWorkOrderId(), actor, entry.getDecisionAtUtc(),
                entry.getRejectionReason()));
        return entry;
    }
}
