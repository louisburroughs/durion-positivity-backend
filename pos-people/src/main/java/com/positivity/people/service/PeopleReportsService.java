package com.positivity.people.service;

import com.positivity.people.internal.dto.ApprovedTimeExportResponse;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PeopleReportsService {

    @NonNull
    List<AttendanceDiscrepancyReportResponse> getAttendanceDiscrepancyReport(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull String timezone,
            UUID locationId,
            @NonNull List<UUID> technicianIds,
            boolean flaggedOnly);

    /**
     * Retrieve approved time entries for accounting export orchestration.
     *
     * @param startDate   inclusive start date (UTC)
     * @param endDate     inclusive end date (UTC)
     * @param locationIds one or more timekeeping location identifiers
     * @return deterministic approved-time dataset
     */
    @NonNull
    List<ApprovedTimeExportResponse> getApprovedTimeForExport(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull List<UUID> locationIds);
}
