package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkUserToPersonRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "personId is required")
    private UUID personId;

    private String linkType = "PRIMARY";

    private String notes;

    public LinkUserToPersonRequest(UUID userId, UUID personId) {
        this.userId = userId;
        this.personId = personId;
    }
}
