package com.positivity.mcp.internal.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0068 §5 / spec §2.2: one question of the System One request, as the wire carries it. The
 * instructions are fixed English text about the shop-management context and the decision; they never
 * include the message, the caller or the tenant (ADR-0068 §4). Every question carries instructions
 * because Ollama requires them on every type (ADR-0068 §5).
 *
 * @param wireName the key under {@code questions} and under {@code answers}; a tag's wire name, or
 *     {@code entity_<n>} for an entity group
 * @param tag the tag the answer feeds; {@link TagName#ENTITY} for every group
 * @param choiceCriteria label to description, in option order; only for {@link
 *     TagName.Primitive#CHOICE}
 * @param scoreLevels the ordered levels, lowest first; only for {@link TagName.Primitive#SCORE}
 */
public record TagQuestion(
        @NonNull String wireName,
        @NonNull TagName tag,
        @NonNull String instructions,
        @NonNull SequencedMap<String, String> choiceCriteria,
        @NonNull List<String> scoreLevels) {

    public TagQuestion {
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("Every question carries instructions: " + wireName);
        }
        choiceCriteria = java.util.Collections.unmodifiableSequencedMap(new LinkedHashMap<>(choiceCriteria));
        scoreLevels = List.copyOf(scoreLevels);
    }

    public static @NonNull TagQuestion noul(@NonNull TagName tag, @NonNull String instructions) {
        return new TagQuestion(tag.wireName(), tag, instructions, new LinkedHashMap<>(), List.of());
    }

    public static @NonNull TagQuestion choice(
            @NonNull TagName tag, @NonNull String instructions, @NonNull SequencedMap<String, String> criteria) {
        return new TagQuestion(tag.wireName(), tag, instructions, criteria, List.of());
    }

    /** An entity group (spec §2.4): {@code entity_<index>} over the group's keys plus {@code none}. */
    public static @NonNull TagQuestion entityGroup(
            int index, @NonNull String instructions, @NonNull SequencedMap<String, String> criteria) {
        return new TagQuestion(TagName.entityGroupName(index), TagName.ENTITY, instructions, criteria, List.of());
    }

    public static @NonNull TagQuestion score(
            @NonNull TagName tag, @NonNull String instructions, @NonNull List<String> levels) {
        return new TagQuestion(tag.wireName(), tag, instructions, new LinkedHashMap<>(), levels);
    }

    public TagName.@NonNull Primitive primitive() {
        return tag.primitive();
    }

    /** The options a Choice or Score answer may take, in order; empty for a Noul question. */
    public @NonNull List<String> options() {
        return switch (primitive()) {
            case NOUL -> List.of();
            case CHOICE -> List.copyOf(choiceCriteria.keySet());
            case SCORE -> scoreLevels;
        };
    }

    /** The JSON object under {@code questions.<wireName>}, exactly the fields the protocol defines. */
    public @NonNull Map<String, Object> toWire() {
        SequencedMap<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", primitive().wireName());
        wire.put("instructions", instructions);
        switch (primitive()) {
            case NOUL -> {
                // no criteria
            }
            case CHOICE -> wire.put("criteria", choiceCriteria);
            case SCORE -> wire.put("criteria", scoreLevels);
        }
        return wire;
    }
}
