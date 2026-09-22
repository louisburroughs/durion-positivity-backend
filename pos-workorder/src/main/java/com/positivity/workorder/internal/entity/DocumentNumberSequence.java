package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The next estimate or workorder number to hand out in one scope (#2150).
 *
 * <p>Read under a {@code FOR UPDATE} lock and advanced inside the transaction that inserts the
 * numbered row, so concurrent creates in the same scope serialize on the row until the winner
 * commits and can never read the same number. See {@code DocumentNumberAllocator}.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "document_number_sequence",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "document_number_sequence_scope_key",
                    columnNames = {"tenant_id", "scope_key"})
        })
public class DocumentNumberSequence extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    /** {@code EST-{year}-{locationId}} or {@code WO-{year}}. */
    @Column(name = "scope_key", length = 64, nullable = false)
    private String scopeKey;

    /** The number this scope hands out next. */
    @Column(name = "next_value", nullable = false)
    private Long nextValue;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
