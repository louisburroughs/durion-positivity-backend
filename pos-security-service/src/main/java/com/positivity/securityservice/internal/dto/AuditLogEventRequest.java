package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating immutable audit events.
 *
 * Issue: #41
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogEventRequest {

    private String eventType;
    private String actorId;
    private String entityId;
    private String entityType;
    private Object oldValue;
    private Object newValue;
    private Object context;
}
