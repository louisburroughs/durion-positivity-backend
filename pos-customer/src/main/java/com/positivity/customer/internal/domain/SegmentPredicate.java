package com.positivity.customer.internal.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.positivity.customer.internal.enums.SegmentOperator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A validated boolean tree describing segment membership (Story #1137).
 *
 * <p>Explicitly a closed algebra rather than free SQL or a query string: every node is one of
 * four shapes and every leaf names an attribute from {@link SegmentAttribute}. A marketer can
 * author arbitrarily nested conditions without any of it reaching the database as text.
 *
 * <p>Serialized to JSON with a {@code nodeType} discriminator and stored on the segment.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "nodeType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SegmentPredicate.Comparison.class, name = "COMPARISON"),
    @JsonSubTypes.Type(value = SegmentPredicate.And.class, name = "AND"),
    @JsonSubTypes.Type(value = SegmentPredicate.Or.class, name = "OR"),
    @JsonSubTypes.Type(value = SegmentPredicate.Not.class, name = "NOT")
})
@Schema(
        description = "Boolean predicate tree over the whitelisted segment attribute catalog",
        discriminatorProperty = "nodeType",
        subTypes = {
            SegmentPredicate.Comparison.class,
            SegmentPredicate.And.class,
            SegmentPredicate.Or.class,
            SegmentPredicate.Not.class
        })
public sealed interface SegmentPredicate
        permits SegmentPredicate.Comparison, SegmentPredicate.And, SegmentPredicate.Or, SegmentPredicate.Not {

    /**
     * A single attribute test.
     *
     * @param attribute wire name from the catalog, e.g. {@code party.accountTier} or the keyed
     *     form {@code party.externalIdentifier[QBO]}
     * @param operator comparison to apply; must be one the attribute allows
     * @param values operands — one for scalar comparisons, many for {@code IN}/{@code CONTAINS_*},
     *     empty for the unary presence and boolean operators
     */
    @Schema(description = "A single attribute test")
    record Comparison(
            @Schema(description = "Attribute wire name", example = "party.accountTier")
            String attribute,

            @Schema(description = "Comparison operator", example = "IN")
            SegmentOperator operator,

            @Schema(description = "Operand values; empty for unary operators") @Nullable
            List<String> values)
            implements SegmentPredicate {

        public Comparison {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    @Schema(description = "All child predicates must match")
    record And(@Schema(description = "Child predicates") List<SegmentPredicate> nodes) implements SegmentPredicate {
        public And {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    @Schema(description = "At least one child predicate must match")
    record Or(@Schema(description = "Child predicates") List<SegmentPredicate> nodes) implements SegmentPredicate {
        public Or {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    @Schema(description = "Inverts the child predicate")
    record Not(@Schema(description = "Predicate to invert") SegmentPredicate node) implements SegmentPredicate {}
}
