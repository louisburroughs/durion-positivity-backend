package com.positivity.people.internal.service;

import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.WorkSessionService;

public class WorkSessionServiceImpl extends WorkSessionService {

    public WorkSessionServiceImpl(
            TimeEntryRepository timeEntryRepository,
            TimeEntryExceptionRepository timeEntryExceptionRepository) {
        super(timeEntryRepository, timeEntryExceptionRepository);
    }
}