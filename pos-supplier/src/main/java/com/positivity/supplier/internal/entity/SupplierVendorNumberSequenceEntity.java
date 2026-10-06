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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The next allocated vendor number of one tenant (#2516; the pos-workorder
 * {@code DocumentNumberSequence} pattern). Read under a {@code FOR UPDATE} lock and advanced in the
 * transaction that inserts the vendor, so two creates can never pick the same {@code V-nnnnnn}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "supplier_vendor_number_sequence")
@EntityListeners(AuditingEntityListener.class)
public class SupplierVendorNumberSequenceEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The number this tenant hands out next. */
    @Column(name = "next_value", nullable = false)
    private Long nextValue;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
