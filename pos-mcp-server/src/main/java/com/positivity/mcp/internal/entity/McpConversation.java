package com.positivity.mcp.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A persisted assistant conversation (#2073), owned by one subject within one tenant (ADR-0062).
 *
 * <p>{@code ownerUserId} is the token subject ({@code CurrentUserContext.userId()}); it is set once
 * on create and never changes. The tenant is stamped by Hibernate from the bound {@code
 * TenantContext} ({@link TenantScopedEntity}), never from request data. {@code updatedAt} is
 * bumped by auditing on every change (new turn, rename, pin toggle) and drives both the history
 * rail order and the retention purge.
 *
 * <p>The chat path pre-assigns the id (UUID v7) so the conversation can key the model's memory before
 * anything is written, and inserts the row only after the model answers. {@link Persistable} keeps
 * that insert a plain {@code persist} rather than a {@code merge} of an unknown id: a new instance is
 * new until it is persisted or loaded.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mcp_conversation")
public class McpConversation extends TenantScopedEntity implements Persistable<UUID> {

    public static final int TITLE_MAX_LENGTH = 120;
    public static final int PREVIEW_MAX_LENGTH = 200;

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** True once the owner renamed the conversation; the server then stops deriving the title. */
    @Column(name = "title_user_set", nullable = false)
    private boolean titleUserSet;

    @Column(length = PREVIEW_MAX_LENGTH)
    private String preview;

    @Column(nullable = false)
    private boolean pinned;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.newEntity = false;
    }
}
