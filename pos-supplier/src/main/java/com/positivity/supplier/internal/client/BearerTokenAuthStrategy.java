package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Static bearer token (ADR-0050 §4): a long-lived token the vendor issued out of band, held as a
 * secret reference and resolved per exchange.
 *
 * <p>Distinct from {@link OAuth2ClientCredentialsAuthStrategy}, which mints short-lived tokens
 * itself. Nothing is cached here — the token is a stored credential, and caching a stored
 * credential in memory would outlive a secret rotation.
 */
@Component
public class BearerTokenAuthStrategy implements SupplierAuthStrategy {

    private final SecretSchemeRegistry secretSchemeRegistry;

    public BearerTokenAuthStrategy(@NonNull SecretSchemeRegistry secretSchemeRegistry) {
        this.secretSchemeRegistry = Objects.requireNonNull(secretSchemeRegistry, "secretSchemeRegistry");
    }

    @Override
    @NonNull
    public SupplierAuthType supportedType() {
        return SupplierAuthType.BEARER;
    }

    @Override
    public void apply(@NonNull HttpHeaders headers, @NonNull SupplierAuthConfigEntity authConfig) {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(authConfig, "authConfig must not be null");

        String reference = authConfig.getBearerTokenRef();
        if (reference == null || reference.isBlank()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.AUTH_CONFIG_MISSING,
                    "Auth config '" + authConfig.getName() + "' of type " + supportedType()
                            + " is missing bearerTokenRef (ADR-0050 §4)");
        }
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + secretSchemeRegistry.resolve(reference));
    }
}
