package com.positivity.securityservice.internal.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerPersonSearchResponse(
        UUID personId,
        String firstName,
        String lastName,
        String displayName,
        List<ContactPointDto> contactPoints,
        boolean individualCustomer,
        boolean commercialContact,
        int commercialAccountCount,
        Instant createdAt,
        Instant updatedAt) {

    public record ContactPointDto(
            UUID contactPointId,
            String contactType,
            String value,
            boolean isPrimary) {
    }
}
