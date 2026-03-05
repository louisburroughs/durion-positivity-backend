package com.positivity.people.internal.dto;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;

public class WorkSessionRequest {

	@NotNull(message = "personId is required")
	@Schema(description = "ID of the person", requiredMode = Schema.RequiredMode.REQUIRED)
	private UUID personId;

	@Schema(description = "Actor performing the action", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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
