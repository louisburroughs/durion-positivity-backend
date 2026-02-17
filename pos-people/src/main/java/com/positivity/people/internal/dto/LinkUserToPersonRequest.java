package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

@Data
@RequiredArgsConstructor
public class LinkUserToPersonRequest {

        @NotBlank(message = "userId is required")
        @NonNull
        private UUID userId;

        @NotNull(message = "personId is required")
        @NonNull
        private UUID personId;

        private String linkType = "PRIMARY";

        private String notes;
}
