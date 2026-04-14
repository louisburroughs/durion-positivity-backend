package com.positivity.securityservice.internal.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Audit event emitted when a permission is granted to a role.
 *
 * Issue: #42
 */
public class RolePermissionGrantAuditEvent extends ApplicationEvent {

    private final String eventType;
    private final String actorId;
    private final UUID roleId;
    private final String permissionKey;
    private final Instant timestamp;

    public RolePermissionGrantAuditEvent(
            Object source, String actorId, UUID roleId, String permissionKey, Instant timestamp) {
        super(source);
        this.eventType = "role.permission.grant";
        this.actorId = actorId;
        this.roleId = roleId;
        this.permissionKey = permissionKey;
        this.timestamp = timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActorId() {
        return actorId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public String getPermissionKey() {
        return permissionKey;
    }

    public Instant getOccurredAt() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "RolePermissionGrantAuditEvent{" + "eventType='"
                + eventType + '\'' + ", actorId='"
                + actorId + '\'' + ", roleId="
                + roleId + ", permissionKey='"
                + permissionKey + '\'' + ", timestamp="
                + timestamp + '}';
    }
}
