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
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One persisted turn of an {@link McpConversation} (#2073). The turn itself (role, origin, content,
 * blocks and, on a chat-path assistant turn, the turn summary) is written once and never changed
 * ({@code updatable = false}). The row is still a mutable entity under ADR-0024: the owner's rating
 * (#2075, the {@code feedback*} columns) is set and cleared by a bulk update, so it carries both audit
 * columns.
 *
 * <p>{@code conversationId} is a plain column rather than a JPA association: messages are only ever
 * read or deleted by conversation id through the repository, and the database carries the composite
 * {@code (tenant_id, conversation_id)} foreign key with {@code ON DELETE CASCADE}. {@code blocks} is
 * the serialized {@code ChatBlock} list (a JSON array, possibly empty) mapped to {@code jsonb};
 * {@code content} is the raw user text or assistant markdown.
 *
 * <p>Turn summary (#2075): {@code answerPath} ({@code AGENT} or {@code SIMPLE_CHAT}), {@code
 * answerSource} (how the answer text was resolved), {@code toolsCalled} (a JSON array of tool names in
 * call order, serialized like {@code blocks}) and {@code latencyMs}. Only an {@code assistant} row
 * with origin {@code CHAT} carries them; they are null everywhere else (enforced by a CHECK).
 *
 * <p>The chat path pre-assigns the id (UUID v7) so the answer's id is known before the model runs.
 * {@link Persistable} keeps that insert a plain {@code persist} rather than a {@code merge} of an
 * unknown id: a new instance is new until it is persisted or loaded.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mcp_message")
public class McpMessage extends TenantScopedEntity implements Persistable<UUID> {

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

    @Column(name = "answer_path", length = 16, updatable = false)
    private String answerPath;

    @Column(name = "answer_source", length = 32, updatable = false)
    private String answerSource;

    /** JSON array of tool names in call order (duplicates kept, at most 64); same handling as blocks. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools_called", updatable = false)
    private String toolsCalled;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "feedback_rating", length = 20)
    private String feedbackRating;

    @Column(name = "feedback_reason", length = 20)
    private String feedbackReason;

    @Column(name = "feedback_comment", length = 1000)
    private String feedbackComment;

    @Column(name = "feedback_at")
    private OffsetDateTime feedbackAt;

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
