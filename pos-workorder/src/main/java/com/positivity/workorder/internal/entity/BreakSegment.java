package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.BreakType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a break segment within a work session.
 * Implements CAP-139 Story #68.
 */
@Entity
@Table(name = "break_segment", indexes = {
        @Index(name = "idx_break_segment_work_session_id", columnList = "workSessionId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class BreakSegment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID breakSegmentId;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_session_id", nullable = false, updatable = false)
    private WorkSession workSession;

    @NonNull
    @Column(name = "work_session_id", nullable = false, updatable = false, insertable = false, columnDefinition = "UUID")
    private UUID workSessionId;

    @NonNull
    @Column(nullable = false)
    private Instant breakStartAt;

    @Nullable
    @Column
    private Instant breakEndAt;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column
    private BreakType breakType;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
