package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Request to start or act on a work session for a person")
public class WorkSessionRequest {

    @NotNull(message = "personId is required")
    @Schema(
            description = "ID of the person",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "Actor performing the action",
            example = "manager.user",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String actor;

    public UUID getPersonId() {
        return personId;
    }

    public void setPersonId(UUID personId) {
        this.personId = personId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }
}
