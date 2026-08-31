package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Security roles with their MCP persona metadata (#1613, D8). Nothing to resolve — a role is
 * identified by its own name.
 *
 * <p>Persona slots are validated here as well as at the ingest endpoint. Catching a bad slot on the
 * loader side puts the row in the review queue with the file and row number attached, which is what
 * an operator can actually act on; a rejection from the endpoint arrives without that context.
 */
@Component
public class RoleLoaderStrategy implements DomainLoaderStrategy<RoleLoaderRecord> {

    /** Mirrors the column widths in V34 and the caps enforced by {@code @PersonaText}. */
    private static final int TITLE_MAX = 60;

    private static final int FOCUS_MAX = 200;
    private static final int TONE_MAX = 120;

    /**
     * Terms that turn a descriptive persona slot into an instruction (#1613 D9 control 1). Kept in
     * step with {@code PersonaTextValidator} in pos-security-service; duplicated rather than shared
     * because the two modules have no common library and a REST-edge contract is not the place for
     * a word list.
     */
    private static final Set<String> CONTROL_TERMS = Set.of(
            "ignore",
            "disregard",
            "override",
            "bypass",
            "skip",
            "omit",
            "suppress",
            "must",
            "shall",
            "always",
            "never",
            "forget",
            "pretend",
            "instead",
            "regardless",
            "unrestricted",
            "unlimited",
            "execute",
            "run",
            "grant",
            "allow",
            "permit",
            "enable",
            "disable",
            "authorize",
            "act as",
            "roleplay",
            "you are",
            "your instructions",
            "system prompt",
            "do not",
            "don't");

    @Override
    public DomainType getDomainType() {
        return DomainType.SECURITY_ROLE;
    }

    @Override
    public RoleLoaderRecord mapRow(@NonNull Map<String, String> row) {
        RoleLoaderRecord record = new RoleLoaderRecord();
        record.setName(row.get("name"));
        record.setDescription(row.get("description"));
        record.setPersonaTitle(row.get("personaTitle"));
        record.setPersonaFocus(row.get("personaFocus"));
        record.setPersonaTone(row.get("personaTone"));
        record.setMcpPersonaRank(row.get("mcpPersonaRank"));
        record.setMcpPersonaEligible(row.get("mcpPersonaEligible"));
        return record;
    }

    @Override
    public List<String> validate(@NonNull RoleLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getName())) {
            errors.add("name is required");
        }
        LoaderValues.requireIntegerOrBlank(item.getMcpPersonaRank(), "mcpPersonaRank", errors);
        validatePersonaSlot(item.getPersonaTitle(), "personaTitle", TITLE_MAX, errors);
        validatePersonaSlot(item.getPersonaFocus(), "personaFocus", FOCUS_MAX, errors);
        validatePersonaSlot(item.getPersonaTone(), "personaTone", TONE_MAX, errors);
        validateBooleanOrBlank(item.getMcpPersonaEligible(), errors);
        return errors;
    }

    /**
     * A blank slot is valid and means "derive it" — a role is never required to carry curated
     * persona fields.
     */
    private static void validatePersonaSlot(
            @Nullable String value, String field, int max, @NonNull List<String> errors) {
        if (LoaderValues.isBlank(value)) {
            return;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            errors.add(field + " must be a single line with no control characters");
            return;
        }
        if (!value.equals(value.strip())) {
            errors.add(field + " must not start or end with whitespace");
            return;
        }
        if (value.length() > max) {
            errors.add("%s must be at most %d characters".formatted(field, max));
            return;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        for (String term : CONTROL_TERMS) {
            if (containsWord(lowered, term)) {
                errors.add("%s must describe the role rather than instruct the assistant; remove \"%s\""
                        .formatted(field, term));
                return;
            }
        }
    }

    /**
     * Matches at a word boundary and absorbs trailing word characters, so "grant" also catches
     * "granting" while "overrun" does not trip "run".
     *
     * <p>The trailing-inflection part matters for parity: {@code PersonaTextValidator} uses
     * {@code \b<term>\w*\b}, so a loader that required a non-word character right after the term
     * would accept "granting exceptions" and let the row through to an endpoint that rejects it —
     * and that rejection is a 400 for the whole batch, with no row attribution, which is the opposite
     * of what validating here is for.
     */
    private static boolean containsWord(String text, String term) {
        int from = 0;
        while (true) {
            int at = text.indexOf(term, from);
            if (at < 0) {
                return false;
            }
            boolean startsWord = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            if (startsWord) {
                return true;
            }
            from = at + 1;
        }
    }

    private static void validateBooleanOrBlank(@Nullable String value, @NonNull List<String> errors) {
        if (LoaderValues.isBlank(value)) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"true".equals(normalized) && !"false".equals(normalized)) {
            errors.add("mcpPersonaEligible must be true or false");
        }
    }
}
