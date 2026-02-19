package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.PeopleReportsService;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class PeopleReportsServiceImpl implements PeopleReportsService {

    private final TimeEntryRepository timeEntryRepository;

    public PeopleReportsServiceImpl(TimeEntryRepository timeEntryRepository) {
        this.timeEntryRepository = timeEntryRepository;
    }

    @Override
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
