package com.positivity.people.internal.dto;

import java.util.UUID;

public class WorkSessionRequest {

    private UUID personId;
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
