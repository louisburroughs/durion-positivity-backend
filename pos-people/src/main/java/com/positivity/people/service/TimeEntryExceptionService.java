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

    boolean actionException(
            @NonNull UUID exceptionId,
            @NonNull ExceptionStatus targetStatus,
            @NonNull String actionUserId,
            String actionNotes,
            String correlationId);

    boolean resolveException(
            @NonNull UUID exceptionId,
            @NonNull String resolverUserId,
            Set<String> permissions,
            String resolutionNotes,
            String resolutionAction,
            String correlationId);
}
