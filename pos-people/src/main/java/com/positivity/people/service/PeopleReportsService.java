package com.positivity.people.service;

import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import org.jspecify.annotations.NonNull;

public interface PeopleReportsService {

    @NonNull
    AttendanceDiscrepancyReportResponse getAttendanceDiscrepancyReport();
}
