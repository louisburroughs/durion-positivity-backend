package com.positivity.people.internal.dto;

public class WorkSessionStartRequest {

    private String personId;
    private String actor;

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }
}
