package com.positivity.tenant.internal.validation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The locales a tenant may be set to (CAP:550 S44, #2618; ADR-0030 §2): {@code pos.tenant.supported-locales}, by
 * default the five the frontend registers. A value is accepted when, in canonical BCP 47 case ({@code fr-ca} is
 * {@code fr-CA}), it names a language and a region and is listed; a bare language such as {@code fr} is not.
 */
@Component
public class TenantLocales {

    private final List<String> supported;

    public TenantLocales(@Value("${pos.tenant.supported-locales:en-US,es-US,es-MX,fr-CA,fr-FR}") List<String> supported) {
        this.supported = supported.stream()
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> Locale.forLanguageTag(tag).toLanguageTag())
                .toList();
    }

    /** The listed locales, in canonical case and configured order. */
    public @NonNull List<String> supported() {
        return supported;
    }

    /** The canonical tag of {@code raw} when it is a listed language-and-region tag; empty otherwise. */
    public @NonNull Optional<String> canonicalOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Locale locale = Locale.forLanguageTag(raw.trim());
        if (locale.getLanguage().isEmpty() || locale.getCountry().isEmpty()) {
            return Optional.empty();
        }
        String canonical = locale.toLanguageTag();
        return supported.stream().filter(canonical::equals).findFirst();
    }

    /** The canonical tag of a value bean validation already accepted. */
    public @NonNull String canonical(@NonNull String accepted) {
        return canonicalOf(accepted)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported locale: " + accepted));
    }
}
