package com.positivity.people.internal.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class UserPersonLinkResponse {
        private UUID linkId;
        private UUID userId;
        private UUID personId;
        private String linkType;
        private Instant createdAt;
        private String createdBy;
        private String notes;
}
