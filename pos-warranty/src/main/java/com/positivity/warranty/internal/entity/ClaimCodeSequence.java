package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-year allocation counter behind the {@code WC-yyyy-nnnnnn} claim code (PRD §4).
 * Table-based (not a native sequence) so allocation works identically on H2 (dev) and
 * Postgres and can be locked pessimistically. The column is {@code code_year} because
 * {@code YEAR} is a reserved word in H2. {@code nextSeq} is the NEXT value to hand out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "claim_code_sequence")
public class ClaimCodeSequence {

    @Id
    @Column(name = "code_year", nullable = false, updatable = false)
    private Integer year;

    @Column(name = "next_seq", nullable = false)
    private Long nextSeq;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: this table is a natural-key
     * allocation counter (one row per calendar year), not a UUID-keyed aggregate.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
