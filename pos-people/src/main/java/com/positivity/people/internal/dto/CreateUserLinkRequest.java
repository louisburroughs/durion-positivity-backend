package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class CreateUserLinkRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "personId is required")
    private UUID personId;
}
