package com.positivity.securityservice.internal.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enforces that a persona slot describes the caller rather than instructing the model (#1613, D9
 * control 1).
 *
 * <p>Four checks, cheapest first:
 *
 * <ol>
 *   <li>single line — no newline, tab, or other control character, so a slot cannot open a new
 *       pseudo-section inside the assembled prompt;
 *   <li>no surrounding whitespace, so the rendered template stays predictable;
 *   <li>length cap, mirroring the column width;
 *   <li>no imperative control verb, which is what separates "warm and customer-ready" from
 *       "ignore the confirmation step".
 * </ol>
 *
 * <p>The control-verb list is deliberately small. Terms match at a word boundary and absorb
 * trailing word characters, so "grant" also catches "grants" and "granting", while a term embedded
 * in a longer word ("overrun" for "run", "execution" for "execute") does not match. It is a
 * structural backstop against the obvious cases, not a content filter — the real containment is
 * that a slot can only ever fill three fixed positions in a template it does not control, and that
 * TOOL_USE and WRITE_GATE state their own precedence over anything above them (D9 control 2).
 */
public class PersonaTextValidator implements ConstraintValidator<PersonaText, String> {

    /**
     * Verbs and phrases that turn a descriptive slot into an instruction, or that reach for the
     * layer contract itself. Matched case-insensitively on whole words, so "approval" does not trip
     * "approve" and "never-ending" does not trip "never".
     */
    private static final List<String> CONTROL_TERMS = List.of(
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

    private static final Pattern CONTROL_TERM_PATTERN = Pattern.compile(
            CONTROL_TERMS.stream()
                    .map(term -> "\\b" + Pattern.quote(term) + "\\w*\\b")
                    .collect(Collectors.joining("|")),
            Pattern.CASE_INSENSITIVE);

    private int max;

    @Override
    public void initialize(PersonaText constraintAnnotation) {
        this.max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Absent means "derive this slot" (D5). A role is never required to carry curated fields.
        if (value == null || value.isBlank()) {
            return true;
        }

        if (containsControlCharacter(value)) {
            return reject(context, "must be a single line with no control characters");
        }
        if (!value.equals(value.strip())) {
            return reject(context, "must not start or end with whitespace");
        }
        if (value.length() > max) {
            return reject(context, "must be at most " + max + " characters");
        }

        var matcher = CONTROL_TERM_PATTERN.matcher(value);
        if (matcher.find()) {
            return reject(
                    context,
                    "must describe the role rather than instruct the assistant; remove \""
                            + matcher.group().toLowerCase(Locale.ROOT) + "\"");
        }
        return true;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
