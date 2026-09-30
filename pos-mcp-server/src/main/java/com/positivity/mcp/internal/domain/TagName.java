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
 * <p>{@link #ENTITY} is the one tag asked as several questions: spec §2.4 splits the lexicon into
 * groups of at most 24 entities because both local models accept at most 26 options per Choice, so the
 * wire carries {@code entity_1 … entity_k}. Thresholds and {@code enforced-tags} name {@code entity},
 * not the groups.
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
    ENTITY("entity", Primitive.CHOICE);

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

    private static final Pattern ENTITY_GROUP = Pattern.compile("entity_(\\d+)");

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

    /** The wire name of entity group {@code index} (1-based): {@code entity_1}, {@code entity_2}, ... */
    public static @NonNull String entityGroupName(int index) {
        return ENTITY.wireName + "_" + index;
    }

    /** The tag behind a wire name; an {@code entity_<n>} group resolves to {@link #ENTITY}. */
    public static @NonNull Optional<TagName> fromWireName(@NonNull String wireName) {
        for (TagName tag : values()) {
            if (tag.wireName.equals(wireName)) {
                return Optional.of(tag);
            }
        }
        return ENTITY_GROUP.matcher(wireName).matches() ? Optional.of(ENTITY) : Optional.empty();
    }

    /** True when {@code wireName} is one of the {@code entity_<n>} groups. */
    public static boolean isEntityGroup(@NonNull String wireName) {
        Matcher matcher = ENTITY_GROUP.matcher(wireName);
        return matcher.matches();
    }
}
