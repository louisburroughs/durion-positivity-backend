package com.positivity.mcp.internal.entity;

import com.positivity.mcp.internal.enums.ConversationMessageOrigin;
import com.positivity.mcp.internal.enums.ConversationMessageRole;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One persisted turn of an {@link McpConversation} (#2073). The turn itself (role, origin, content,
 * blocks) is written once and never changed ({@code updatable = false}). The row is still a mutable
 * entity under ADR-0024 — Wave 4 (#2075) records per-turn feedback on it by update — so it carries
 * both audit columns.
 *
 * <p>{@code conversationId} is a plain column rather than a JPA association: messages are only ever
 * read or deleted by conversation id through the repository, and the database carries the composite
 * {@code (tenant_id, conversation_id)} foreign key with {@code ON DELETE CASCADE}. {@code blocks} is
 * the serialized {@code ChatBlock} list (a JSON array, possibly empty) mapped to {@code jsonb};
 * {@code content} is the raw user text or assistant markdown.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mcp_message")
public class McpMessage extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Convert(converter = ConversationMessageRoleConverter.class)
    @Column(nullable = false, updatable = false, length = 16)
    private ConversationMessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ConversationMessageOrigin origin;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String blocks;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
