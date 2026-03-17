package com.positivity.securityservice.internal.client.dto;

import lombok.Builder;

@Builder
public record PeopleResolvePersonRequest(
        String email,
        String phone,
        String lastName,
        String firstName,
        Integer threshold) {
}
