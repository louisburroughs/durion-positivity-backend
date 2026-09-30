package com.positivity.mcp.internal.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0068 §1: the closed tag set. Adding a tag is a code change; nothing here is configurable at
 * runtime. The wire name is the key of the question in the Jev request and of the answer in its
 * response, and the name every threshold, trace entry and meter uses.
 *
 * <p>{@link #ENTITY} is the one tag asked as several questions: one Noul per lexicon entity, so the
 * wire carries {@code entity_<key>} ({@code entity_work-order}). Thresholds and {@code enforced-tags}
 * name {@code entity} (a per-entity threshold may be set as {@code thresholds.entity.<key>}).
 */
public enum TagName {
    FOLLOWS_PREVIOUS_TURN("follows_previous_turn", Primitive.NOUL),
    SIMPLE_CHAT("simple_chat", Primitive.NOUL),
    WORKFLOW_STATE("workflow_state", Primitive.CHOICE),
    NEEDS_WEB_SEARCH("needs_web_search", Primitive.NOUL),
    ABOUT_INVENTORY("about_inventory", Primitive.NOUL),
    ABOUT_ORDERS("about_orders", Primitive.NOUL),
    IMPLIES_DATE_WINDOW("implies_date_window", Primitive.NOUL),
    ADMIN_ACCOUNT_QUESTION("admin_account_question", Primitive.NOUL),
    COMPOUND_QUESTION("compound_question", Primitive.NOUL),
    INTENT("intent", Primitive.CHOICE),
    COMPLEXITY("complexity", Primitive.CHOICE),
    RISK("risk", Primitive.SCORE),
    DOMAIN("domain", Primitive.CHOICE),
    ENTITY("entity", Primitive.NOUL);

    /** The three System One question primitives (ADR-0068, Drivers). */
    public enum Primitive {
        NOUL,
        CHOICE,
        SCORE;

        /** The {@code type} the wire uses. */
        public @NonNull String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern ENTITY_WIRE_NAME = Pattern.compile("entity_([a-z0-9][a-z0-9-]*)");

    private final String wireName;
    private final Primitive primitive;

    TagName(String wireName, Primitive primitive) {
        this.wireName = wireName;
        this.primitive = primitive;
    }

    public @NonNull String wireName() {
        return wireName;
    }

    public @NonNull Primitive primitive() {
        return primitive;
    }

    /** The wire name of the entity Noul for lexicon entity {@code key}: {@code entity_<key>}. */
    public static @NonNull String entityWireName(@NonNull String key) {
        return ENTITY.wireName + "_" + key;
    }

    /** The lexicon entity key behind an {@code entity_<key>} wire name; empty for any other name. */
    public static @NonNull Optional<String> entityKey(@NonNull String wireName) {
        Matcher matcher = ENTITY_WIRE_NAME.matcher(wireName);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** True when {@code wireName} is one of the {@code entity_<key>} Nouls. */
    public static boolean isEntityQuestion(@NonNull String wireName) {
        return ENTITY_WIRE_NAME.matcher(wireName).matches();
    }

    /** The tag behind a wire name; an {@code entity_<key>} name resolves to {@link #ENTITY}. */
    public static @NonNull Optional<TagName> fromWireName(@NonNull String wireName) {
        for (TagName tag : values()) {
            if (tag.wireName.equals(wireName)) {
                return Optional.of(tag);
            }
        }
        return isEntityQuestion(wireName) ? Optional.of(ENTITY) : Optional.empty();
    }
}
