package com.positivity.people.service;

import com.positivity.people.internal.dto.ApprovalPersonDto;
import com.positivity.people.internal.dto.TimePeriodApprovalDto;
import com.positivity.people.internal.dto.TimePeriodDecisionResponse;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimekeepingEntryDto;
import java.util.List;
import java.util.UUID;

public interface TimekeepingApprovalService {

    List<ApprovalPersonDto> listApprovalPeople();

    List<TimePeriodDto> listTimePeriods(UUID tenantId);

    List<TimekeepingEntryDto> listTimekeepingEntries(UUID personId, UUID timePeriodId);

    TimePeriodApprovalDto getTimePeriodApproval(UUID personId, UUID timePeriodId);

    TimePeriodDecisionResponse approvePeriod(UUID timePeriodId, UUID personId);

    TimePeriodDecisionResponse rejectPeriod(UUID timePeriodId, UUID personId, String reason);
}
