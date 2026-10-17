package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The idempotency key of one vendor create (#2678): the client's {@code requestId}, the hash of the payload it was
 * used with, the vendor it created and the 201 body it answered. Written in the create's transaction; a retry with the
 * same key and payload replays the body, another payload is refused. The id is the client's UUID, assigned, which the
 * UUIDv7 generator keeps (ADR-0013: the client mints a v7).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_vendor_create_request")
@EntityListeners(AuditingEntityListener.class)
public class SupplierVendorCreateRequestEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "request_id", updatable = false, nullable = false)
    private UUID requestId;

    /** SHA-256 of the canonical payload, so a replay with other fields is told apart from a retry. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    /** The {@code VendorView} the create answered, kept verbatim so the retry gets the same body. */
    @Column(name = "response_json", nullable = false, updatable = false, columnDefinition = "text")
    private String responseJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
