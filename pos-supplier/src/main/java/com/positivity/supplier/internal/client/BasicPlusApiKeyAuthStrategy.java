package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * HTTP Basic plus a separate API-key header — the EDIWheel/Michelin shape (ADR-0050 §4): the
 * vendor authenticates the caller with username/password <em>and</em> identifies the integration
 * with an API key.
 *
 * <p>All three references are resolved per exchange and nothing is cached. The Basic header is
 * built here rather than through {@code HttpHeaders#setBasicAuth} so the encoding charset is
 * explicit: vendor credentials contain non-ASCII characters often enough that a
 * platform-default-charset dependency would be a latent, environment-specific auth failure.
 */
@Component
public class BasicPlusApiKeyAuthStrategy implements SupplierAuthStrategy {

    /**
     * Header used when a binding's auth config does not name one. Michelin/EDIWheel's documented
     * default; overridable per auth config because other vendors in the same protocol family use
     * {@code X-API-Key}.
     */
    static final String DEFAULT_API_KEY_HEADER = "apikey";

    private final SecretSchemeRegistry secretSchemeRegistry;

    public BasicPlusApiKeyAuthStrategy(@NonNull SecretSchemeRegistry secretSchemeRegistry) {
        this.secretSchemeRegistry = Objects.requireNonNull(secretSchemeRegistry, "secretSchemeRegistry");
    }

    @Override
    @NonNull
    public SupplierAuthType supportedType() {
        return SupplierAuthType.BASIC_PLUS_APIKEY;
    }

    @Override
    public void apply(@NonNull HttpHeaders headers, @NonNull SupplierAuthConfigEntity authConfig) {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(authConfig, "authConfig must not be null");

        String username = resolve(authConfig, "usernameRef", authConfig.getUsernameRef());
        String password = resolve(authConfig, "passwordRef", authConfig.getPasswordRef());
        String apiKey = resolve(authConfig, "apiKeyRef", authConfig.getApiKeyRef());

        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        headers.set(apiKeyHeaderName(authConfig), apiKey);
    }

    /** The configured header name, falling back to the family default. */
    @NonNull
    static String apiKeyHeaderName(@NonNull SupplierAuthConfigEntity authConfig) {
        String configured = authConfig.getApiKeyHeader();
        return (configured == null || configured.isBlank()) ? DEFAULT_API_KEY_HEADER : configured;
    }

    @NonNull
    private String resolve(@NonNull SupplierAuthConfigEntity authConfig, @NonNull String field, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.AUTH_CONFIG_MISSING,
                    "Auth config '" + authConfig.getName() + "' of type " + supportedType() + " is missing " + field
                            + " (ADR-0050 §4)");
        }
        return secretSchemeRegistry.resolve(reference);
    }
}
