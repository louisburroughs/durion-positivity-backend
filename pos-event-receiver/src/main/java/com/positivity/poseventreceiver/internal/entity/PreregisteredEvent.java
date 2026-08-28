package com.positivity.poseventreceiver.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "preregistered_event")
public class PreregisteredEvent {
    @Id
    private String id;

    public PreregisteredEvent() {}

    public PreregisteredEvent(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: the id is the preregistered
     * event type code (e.g. {@code ORDER_ORDER_CREATE}), a natural key supplied by the
     * registering module, not a UUID-keyed aggregate.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
