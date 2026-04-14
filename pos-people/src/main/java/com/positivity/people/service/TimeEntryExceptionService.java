package com.positivity.people.service;

import com.positivity.people.internal.dto.TimeEntryException;
import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.enums.ExceptionStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TimeEntryExceptionService {

    @NonNull
    TimeEntryExceptionResponse createException(@NonNull TimeEntryExceptionRequest request);

    @NonNull
    List<TimeEntryException> listByEmployee(String employeeId);

    void actionException(
            @NonNull UUID exceptionId, @NonNull ExceptionStatus targetStatus, String actionNotes, String correlationId);

    void resolveException(
            @NonNull UUID exceptionId,
            Set<String> permissions,
            String resolutionNotes,
            String resolutionAction,
            String correlationId);
}
