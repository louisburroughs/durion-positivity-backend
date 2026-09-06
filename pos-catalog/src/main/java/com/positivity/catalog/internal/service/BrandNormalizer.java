package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogEnrichmentProperties;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reduces two independently authored brand strings to a form that can be compared for equality
 * (#1645).
 *
 * <h2>Why brand is a hard gate rather than more text in the score</h2>
 *
 * #1352 folded brand into the same trigram bag as the design name, which means a strong design-name
 * resemblance could outvote a brand disagreement: "Pilot Sport 4S" scores well against a Continental
 * product whose name happens to share the words. Brand is the one field where disagreement is
 * decisive rather than probabilistic — no amount of design-text similarity makes a Michelin design
 * describe a Continental tyre — so it is checked first, and a mismatch ends the comparison.
 *
 * <h2>Normalisation rules, in order</h2>
 *
 * <ol>
 *   <li>Lower-case, using {@link Locale#ROOT} so a deployment's locale cannot change what "I"
 *       lower-cases to.
 *   <li>Strip a trailing legal suffix — {@code inc}, {@code inc.}, {@code incorporated},
 *       {@code corp}, {@code corporation}, {@code co}, {@code company}, {@code ltd},
 *       {@code limited}, {@code llc}, {@code plc}, {@code gmbh}, {@code ag}, {@code sa},
 *       {@code sas}, {@code srl}, {@code spa}, {@code bv}, {@code nv}, {@code oy}, {@code ab},
 *       {@code as}, {@code kk}, {@code pty}, {@code group}, {@code holdings} — optionally preceded
 *       by a comma. Only at the end, and only as a whole word: "Continental" must not become
 *       "Contin" because it ends in the letters of no suffix, and "AG Tyres" keeps its AG.
 *   <li>Remove everything that is not a letter or a digit, which disposes of spaces, hyphens,
 *       ampersands, apostrophes and the remaining punctuation in one step.
 *   <li>Resolve the result through the configured alias map
 *       ({@code pos.catalog.enrichment.brand-aliases}), then normalise the canonical value the same
 *       way, so an alias target may itself be written with spaces and suffixes.
 * </ol>
 *
 * <p>Aliases are resolved once, not transitively: a chain would let a configuration mistake produce
 * a cycle, and the failure mode of "alias to an alias" is a missing match a person notices, while
 * the failure mode of a cycle is a hung consumer nobody sees.
 */
@Component
@RequiredArgsConstructor
public class BrandNormalizer {

    /** Whole-word legal suffixes stripped from the end of a brand before comparison. */
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "inc",
            "incorporated",
            "corp",
            "corporation",
            "co",
            "company",
            "ltd",
            "limited",
            "llc",
            "plc",
            "gmbh",
            "ag",
            "sa",
            "sas",
            "srl",
            "spa",
            "bv",
            "nv",
            "oy",
            "ab",
            "as",
            "kk",
            "pty",
            "group",
            "holdings");

    private final CatalogEnrichmentProperties properties;

    /**
     * The comparable form of a brand, or {@code null} when there is nothing to compare.
     *
     * @param brand a brand as some system spelled it; may be null or blank
     * @return the normalised, alias-resolved brand, or null when {@code brand} carries no letters or
     *     digits
     */
    @Nullable
    public String normalize(@Nullable String brand) {
        String normalized = reduce(brand);
        if (normalized == null) {
            return null;
        }
        Map<String, String> aliases = properties.brandAliasesOrEmpty();
        String canonical = aliases.get(normalized);
        if (canonical == null) {
            return normalized;
        }
        String reducedCanonical = reduce(canonical);
        return reducedCanonical != null ? reducedCanonical : normalized;
    }

    /**
     * Whether two brands are the same brand.
     *
     * <p>An absent brand on either side is not agreement. A design with no brand cannot be gated on
     * one, and treating "unknown" as "matches anything" would make the gate disappear exactly where
     * the vendor data is thinnest — which is where guessing is least defensible.
     */
    public boolean sameBrand(@Nullable String left, @Nullable String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    /** Steps 1-3: lower-case, strip a trailing legal suffix, drop non-alphanumerics. */
    @Nullable
    private static String reduce(@Nullable String brand) {
        if (brand == null || brand.isBlank()) {
            return null;
        }
        String lower = brand.toLowerCase(Locale.ROOT).trim();
        String withoutSuffix = stripTrailingLegalSuffix(lower);
        StringBuilder alphanumeric = new StringBuilder(withoutSuffix.length());
        for (int i = 0; i < withoutSuffix.length(); i++) {
            char c = withoutSuffix.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                alphanumeric.append(c);
            }
        }
        return alphanumeric.isEmpty() ? null : alphanumeric.toString();
    }

    /**
     * Removes trailing legal suffixes, repeatedly — "Michelin Group Holdings" and "Foo Co., Ltd."
     * both need more than one pass, and stopping after the first leaves two spellings of one brand.
     * The last remaining word is never stripped: a brand legitimately named "Group" would otherwise
     * normalise to nothing.
     */
    private static String stripTrailingLegalSuffix(String lower) {
        String current = lower;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String trimmed = current.strip();
            while (trimmed.endsWith(".") || trimmed.endsWith(",")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
            }
            int lastSeparator = Math.max(trimmed.lastIndexOf(' '), trimmed.lastIndexOf(','));
            if (lastSeparator <= 0) {
                return trimmed;
            }
            String lastWord = trimmed.substring(lastSeparator + 1).strip();
            if (LEGAL_SUFFIXES.contains(lastWord)) {
                current = trimmed.substring(0, lastSeparator).strip();
                while (current.endsWith(",")) {
                    current = current.substring(0, current.length() - 1).strip();
                }
                stripped = true;
            } else {
                return trimmed;
            }
        }
        return current;
    }
}
