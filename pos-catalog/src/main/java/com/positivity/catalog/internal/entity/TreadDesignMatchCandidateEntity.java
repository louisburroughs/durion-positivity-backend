package com.positivity.catalog.internal.entity;

import com.positivity.catalog.internal.enums.MatchTier;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One product the matcher scored against one tread design, and how well (#1645).
 *
 * <h2>Why the near misses are kept</h2>
 *
 * #1352 scored every candidate and then threw the scores away, keeping only the winners as
 * {@code product.tread_design_id}. That left a reviewer looking at an unmatched design with no way
 * to tell "nothing resembled this" from "two things resembled it equally" — the two cases that need
 * opposite responses. Keeping the scored candidates makes the machine's reasoning inspectable, and
 * makes the ambiguity rule computable across designs rather than only within one pass.
 *
 * <h2>Replaced wholesale per design</h2>
 *
 * Candidates are an observation about the design's current content, not history: when the design's
 * {@code contentHash} changes and matching re-runs, the previous observations describe words the
 * vendor no longer publishes. The unique constraint on {@code (tread_design_id, product_id)} is
 * what keeps a re-run from accumulating duplicate opinions about the same pair.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "tread_design_match_candidate",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_tread_design_match_candidate_design_product",
                        columnNames = {"tread_design_id", "product_id"}))
public class TreadDesignMatchCandidateEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tread_design_id", nullable = false, updatable = false)
    private UUID treadDesignId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /**
     * Similarity in {@code [0.0000, 1.0000]}, four decimals. Stored as a scaled decimal rather than
     * a float so that a threshold comparison — the one thing this number exists for — is exact and
     * reproduces the tier that was recorded alongside it.
     */
    @Column(name = "score", nullable = false, precision = 5, scale = 4)
    private BigDecimal score;

    /**
     * The tier {@link #score} fell in when it was recorded. Stored, not derived on read: thresholds
     * are configuration, and a row must still explain the decision that was actually taken after
     * someone retunes them.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 20)
    private MatchTier tier;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
