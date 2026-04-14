package com.positivity.people.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

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
