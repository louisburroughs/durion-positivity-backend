package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import com.positivity.people.internal.dto.TimeEntrySummary;
import com.positivity.people.internal.enums.TimeEntryStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TimeEntryService {

    /**
     * The approvals queue: attendance entries — clock-in, clock-out, breaks — matching the
     * supplied filters, oldest submission first.
     *
     * @param status keep only entries in this status; null returns every status
     * @param workDate keep only entries whose clock-in falls on this calendar day in
     *     {@code zoneId}; null returns every day
     * @param zoneId the zone whose calendar day {@code workDate} names; the entries store
     *     instants, so the day is only defined once a zone is chosen
     * @param employeeId keep only this person's entries; null returns every person
     * @param locationId keep only entries clocked at this location; null returns every location
     */
    @NonNull
    PagedResponse<TimeEntrySummary> listTimeEntries(
            TimeEntryStatus status,
            LocalDate workDate,
            ZoneId zoneId,
            UUID employeeId,
            UUID locationId,
            int page,
            int size);

    /**
     * One attendance entry by id, for the detail pane the approvals screen opens from a queue row.
     *
     * @throws com.positivity.people.internal.exception.NotFoundException when no entry has that id
     */
    @NonNull
    TimeEntrySummary getTimeEntry(@NonNull UUID timeEntryId, @NonNull ZoneId zoneId);

    @NonNull
    List<TimeEntryDecisionResult> approveEntries(List<String> timeEntryIds, String correlationId);

    @NonNull
    List<TimeEntryDecisionResult> rejectEntries(
            List<String> timeEntryIds, Map<String, String> rejectionReasons, String correlationId);
}
