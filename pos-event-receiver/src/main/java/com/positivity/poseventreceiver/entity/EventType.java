package com.positivity.poseventreceiver.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EventType represents a classification or category of preregistered events.
 * Maps to PreregisteredEvent via eventTypeId.
 */
@Entity
@Table(name = "event_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String typeCode;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    public EventType(String typeCode, String description) {
        this.typeCode = typeCode;
        this.description = description;
        this.active = true;
    }
}
