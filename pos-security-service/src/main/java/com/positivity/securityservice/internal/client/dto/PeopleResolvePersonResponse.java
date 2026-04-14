package com.positivity.securityservice.internal.client.dto;

import java.util.List;
import java.util.UUID;

public record PeopleResolvePersonResponse(
        UUID personId,
        boolean matchedExisting,
        int score,
        int thresholdApplied,
        List<String> matchedBy,
        String firstName,
        String lastName,
        String primaryEmail,
        List<String> phoneNumbers) {}
