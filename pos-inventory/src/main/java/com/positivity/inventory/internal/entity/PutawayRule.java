package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "putaway_rule")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PutawayRule {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID ruleId;

    @Column(nullable = false)
    private Integer priority;

    /**
     * Which tier this rule matches at (issue #1514). Replaces the dead {@code criteria} JSON column
     * that no production code ever read; see {@code V42__putaway_rule_match_criteria.sql}.
     *
     * <p>Deliberately no {@code @Builder.Default}. The only defensible default would be {@code ANY},
     * and {@code ANY} matches every line of every receipt — a builder call that forgot this field
     * would silently produce a catch-all that hijacks the whole rule set. Left unset it is rejected
     * loudly instead: {@code @NotNull} on the request body at the edge, and NOT NULL plus a CHECK
     * constraint in the database.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private PutawayRuleMatchType matchType;

    /**
     * The id this rule matches against, as text: a product id for {@code SKU}, a subcategory id for
     * {@code SUBCATEGORY}, a category id for {@code CATEGORY}, and null for {@code ANY}. Stored as
     * text rather than {@code uuid} because one column serves three different id spaces and a typed
     * column would claim a foreign-key-like relationship to whichever one it named — pos-inventory
     * owns none of them.
     */
    @Column(name = "match_value", length = 128)
    private String matchValue;

    @Column(nullable = false)
    private UUID destinationLocationId;

    /**
     * Destination-selection strategy (odoo-parity K2, issue #1055). Defaults to
     * {@link PutawayDestinationStrategy#FIXED} so pre-K2 rules keep suggesting
     * their configured {@code destinationLocationId} unchanged.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PutawayDestinationStrategy destinationStrategy = PutawayDestinationStrategy.FIXED;

    @Column(nullable = false)
    @Builder.Default
    private boolean isEnabled = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
