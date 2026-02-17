package com.positivity.people.service;

import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.TimeEntryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PeopleReportsService {

    private final TimeEntryRepository timeEntryRepository;

    public PeopleReportsService(TimeEntryRepository timeEntryRepository) {
        this.timeEntryRepository = timeEntryRepository;
    }

    public AttendanceDiscrepancyReportResponse getAttendanceDiscrepancyReport() {
        long approvedCount = timeEntryRepository.countByStatus(TimeEntryStatus.APPROVED);
        long pendingApprovalCount = timeEntryRepository.countByStatus(TimeEntryStatus.PENDING_APPROVAL);
        long rejectedCount = timeEntryRepository.countByStatus(TimeEntryStatus.REJECTED);

        return new AttendanceDiscrepancyReportResponse(
                Instant.now(),
                approvedCount,
                pendingApprovalCount,
                rejectedCount);
    }
}
