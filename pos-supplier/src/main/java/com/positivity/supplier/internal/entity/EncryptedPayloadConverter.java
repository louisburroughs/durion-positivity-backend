package com.positivity.supplier.internal.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * JPA boundary for encrypted exchange-audit payloads (binding decision 1): a {@code String} in the
 * entity, AES-256-GCM ciphertext in a {@code bytea} column.
 *
 * <p>Deliberately thin — all crypto lives in {@link AuditPayloadCipher}, which is unit-testable
 * without JPA. A {@link Component} rather than the repo's usual static converter because it needs
 * the configured key; Spring Boot's {@code SpringBeanContainer} lets Hibernate resolve converters as
 * beans.
 *
 * <p><strong>{@code null} maps to {@code null} in both directions.</strong> That distinction is
 * load-bearing: a {@code METADATA_ONLY} binding, and any row whose payloads have been purged past
 * the retention window (ADR-0050 §7), legitimately has no payload. Encrypting or rejecting
 * {@code null} would make a normal state look like an error, and would make purged rows unreadable
 * instead of simply payload-free. An empty string is a different thing from absent and round-trips
 * as an empty string.
 *
 * <p>Not {@code autoApply}: it must apply only to payload columns, never to every {@code String} on
 * the entity, or metadata like the correlation id and endpoint would be encrypted too and become
 * unqueryable.
 */
@Converter
@Component
public class EncryptedPayloadConverter implements AttributeConverter<String, byte[]> {

    private final AuditPayloadCipher cipher;

    public EncryptedPayloadConverter(@NonNull AuditPayloadCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
    }

    @Override
    public byte @Nullable [] convertToDatabaseColumn(@Nullable String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    @Nullable
    public String convertToEntityAttribute(byte @Nullable [] dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
