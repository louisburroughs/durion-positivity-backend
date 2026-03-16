package com.positivity.securityservice.internal.client.dto;

import java.time.Instant;
import java.util.UUID;

public record PeopleUserLinkResponse(
        UUID linkId,
        UUID userId,
        UUID personId,
        String linkType,
        Instant createdAt,
        String createdBy,
        String notes) {
}
