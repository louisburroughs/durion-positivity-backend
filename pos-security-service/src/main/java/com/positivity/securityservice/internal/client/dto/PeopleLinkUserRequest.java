package com.positivity.securityservice.internal.client.dto;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PeopleLinkUserRequest(UUID userId, UUID personId, String linkType, String notes) {}
