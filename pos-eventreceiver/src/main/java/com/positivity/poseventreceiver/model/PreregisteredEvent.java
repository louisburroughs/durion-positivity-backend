package com.positivity.poseventreceiver.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "preregistered_event")
public class PreregisteredEvent {
    @Id
    private String id;

    public PreregisteredEvent() {}
    public PreregisteredEvent(String id) { this.id = id; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
