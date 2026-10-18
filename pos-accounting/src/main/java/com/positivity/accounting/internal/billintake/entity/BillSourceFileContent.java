package com.positivity.accounting.internal.billintake.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The bytes of a {@link BillSourceFile}, kept apart so the metadata row outlives them (ADR-0070 Decision 8): the
 * purge deletes this row. Encrypted at rest by the database, as the bank statement file is.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "bill_source_file_content")
public class BillSourceFileContent extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @ToString.Include
    @Id
    @AssignedIdentifier("the owning bill_source_file's own UUIDv7; the content row is 1:1 with that file")
    @Column(name = "file_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID fileId;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    public BillSourceFileContent(UUID fileId, byte[] content) {
        this.fileId = fileId;
        this.content = content;
    }
}
