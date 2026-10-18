package com.positivity.inboundmail.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The bytes of a staged attachment, 1:1 with its {@link StagedAttachment} and kept apart so the
 * metadata row is cheap to list and the bytes are deleted by a plain row delete. Under the same row-level
 * security as the metadata, so another tenant's ref reads as absent. Never logged.
 */
@Entity
@Table(name = "staged_attachment_content")
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "content")
public class StagedAttachmentContent extends TenantScopedEntity {

    @Id
    @AssignedIdentifier("the owning staged_attachment's own ref; the content row is 1:1 with it")
    @Column(name = "ref", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID ref;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    public StagedAttachmentContent(UUID ref, byte[] content) {
        this.ref = ref;
        this.content = content;
    }
}
