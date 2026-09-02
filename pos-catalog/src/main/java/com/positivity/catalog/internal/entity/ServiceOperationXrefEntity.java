package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One vendor-operation-code to Durion-service mapping (#1569, sourcing plan §4.2). A feed line
 * whose (source, vendor code) has no row here lands in the unmapped-operation queue — the
 * import never guesses a mapping.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service_operation_xref")
public class ServiceOperationXrefEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "source_code", nullable = false)
    private String sourceCode;

    @Column(name = "provider_op_code", nullable = false)
    private String providerOpCode;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
