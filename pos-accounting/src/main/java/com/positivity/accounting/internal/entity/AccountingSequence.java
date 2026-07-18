package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Accounting Sequence - named counter row backing posted-entry numbering
 * (story A2, issue #942, decision D-1).
 *
 * <p>One row per scope; journal-entry numbering uses one scope per entry
 * month, keyed {@code JE-&#123;YYYYMM&#125;} (derived from the entry's
 * <em>transaction date</em>, not its posting instant, so late-posted entries
 * number into their transaction month). The next number to assign is
 * {@code nextValue}; assignment reads the row under a {@code FOR UPDATE} lock
 * ({@code AccountingSequenceRepository.findByScopeKey}) and increments it in
 * the same transaction as the consuming state change, so concurrent assigners
 * serialize on the row lock and a rolled-back consumer rolls the increment
 * back with it — gapless as a side effect of post-time assignment (D-1; no
 * statutory gapless guarantee is claimed).
 *
 * <p>Rows are bootstrapped on first use at {@code nextValue = 1} by
 * {@code AccountingSequenceProvisioner}; the bootstrap consumes no numbers.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(
        name = "accounting_sequence",
        uniqueConstraints = {@UniqueConstraint(name = "uq_accounting_sequence_scope_key", columnNames = "scope_key")})
public class AccountingSequence {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "sequence_id", nullable = false, columnDefinition = "UUID")
    private UUID sequenceId;

    /**
     * Natural key of the sequence, e.g. {@code JE-202607} for journal-entry
     * numbering in transaction month 2026-07. Unique.
     */
    @Column(name = "scope_key", length = 20, nullable = false)
    private String scopeKey;

    /**
     * The next number this sequence will hand out (1-based).
     */
    @Column(name = "next_value", nullable = false)
    private Long nextValue;
}
