package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateUserLinkRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "personId is required")
    private UUID personId;

}
