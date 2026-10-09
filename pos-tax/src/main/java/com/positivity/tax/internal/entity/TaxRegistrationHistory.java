package com.positivity.tax.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One change to a tax registration (CAP:550 S32c): old to new, the actor (the forwarded {@code X-User-Id}), the
 * justification and the request id. {@code (tenant_id, request_id)} is unique, so a replayed request finds its first
 * result here instead of writing again. The states are JSON snapshots of the registration; they carry its number,
 * so {@code toString} leaves them out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tax_registration_history")
public class TaxRegistrationHistory extends TenantScopedEntity {

    /** {@code CREATE} or {@code UPDATE}. */
    public static final String CREATE = "CREATE";

    public static final String UPDATE = "UPDATE";

    @Id
    @UUIDv7Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "registration_id", nullable = false, updatable = false)
    private UUID registrationId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "change_type", nullable = false, updatable = false, length = 16)
    private String changeType;

    /** The registration before the change; {@code null} on create. */
    @ToString.Exclude
    @Column(name = "old_state", updatable = false, columnDefinition = "text")
    private String oldState;

    @ToString.Exclude
    @Column(name = "new_state", nullable = false, updatable = false, columnDefinition = "text")
    private String newState;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "justification", nullable = false, updatable = false, length = 1000)
    private String justification;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
