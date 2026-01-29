package com.positivity.poseventreceiver.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for EventType API requests and responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventTypeRequest {
    private String typeCode;
    private String description;
    private boolean active;

    public EventTypeRequest(String typeCode, String description) {
        this.typeCode = typeCode;
        this.description = description;
        this.active = true;
    }
}
