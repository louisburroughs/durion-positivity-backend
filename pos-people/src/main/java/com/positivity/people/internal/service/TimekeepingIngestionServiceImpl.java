package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.WorkSessionCompletedEvent;
import com.positivity.people.internal.dto.WorkSessionCorrectedEvent;
import com.positivity.people.internal.entity.TimekeepingEntry;
import com.positivity.people.internal.enums.ApprovalStatus;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import com.positivity.people.service.TimekeepingIngestionService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimekeepingIngestionServiceImpl implements TimekeepingIngestionService {

    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(TimekeepingIngestionServiceImpl.class);

    private final TimekeepingEntryRepository timekeepingEntryRepository;

    public TimekeepingIngestionServiceImpl(TimekeepingEntryRepository timekeepingEntryRepository, Clock clock) {
        this.clock = clock;
        this.timekeepingEntryRepository = timekeepingEntryRepository;
    }

    @Override
    @Transactional
    public void ingestWorkSession(@NonNull WorkSessionCompletedEvent event) {
        if (event.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (event.sessionId() == null) {
            throw new IllegalArgumentException("sessionId is required");
        }

        boolean alreadyExists = timekeepingEntryRepository.existsByTenantIdAndSourceSystemAndSourceSessionId(
                event.tenantId(), "shopmgr", event.sessionId());
        if (alreadyExists) {
            log.debug(
                    "Duplicate work session ingestion ignored: tenantId={}, sessionId={}",
                    event.tenantId(),
                    event.sessionId());
            return;
        }

        TimekeepingEntry entry = new TimekeepingEntry();
        entry.setTimekeepingEntryId(UUIDv7Generator.generate());
        entry.setTenantId(event.tenantId());
        entry.setSourceSystem("shopmgr");
        entry.setSourceSessionId(event.sessionId());
        entry.setEmployeeId(event.employeeId());
        entry.setSessionStartTime(event.startTime());
        entry.setSessionEndTime(event.endTime());
        entry.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
        entry.setAssociatedWorkOrderId(event.workOrderId());
        entry.setCreatedAt(Instant.now(clock));

        timekeepingEntryRepository.save(entry);
    }

    @Override
    @Transactional
    public void recordCorrection(@NonNull WorkSessionCorrectedEvent event) {
        TimekeepingEntry correctionEntry = new TimekeepingEntry();
        correctionEntry.setTimekeepingEntryId(UUIDv7Generator.generate());
        correctionEntry.setTenantId(event.tenantId());
        correctionEntry.setSourceSystem("shopmgr");
        correctionEntry.setSourceSessionId(event.correctionId());
        correctionEntry.setOriginalSourceSessionId(event.originalSessionId());
        correctionEntry.setCorrectionId(event.correctionId());
        correctionEntry.setCorrectionReason(event.correctionReason());
        correctionEntry.setEmployeeId(event.employeeId());
        correctionEntry.setSessionStartTime(event.startTime());
        correctionEntry.setSessionEndTime(event.endTime());
        correctionEntry.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
        correctionEntry.setAssociatedWorkOrderId(event.workOrderId());
        correctionEntry.setCreatedAt(Instant.now(clock));

        timekeepingEntryRepository.save(correctionEntry);
    }
}
