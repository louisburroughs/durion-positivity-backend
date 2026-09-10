package com.positivity.tenancy.kafka;

import com.positivity.tenancy.TenantHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads and writes the {@value TenantHeaders#KAFKA_TENANT_ID} record header. */
public final class TenantKafkaHeaders {

    private static final Logger log = LoggerFactory.getLogger(TenantKafkaHeaders.class);

    private TenantKafkaHeaders() {}

    /** A record for {@code topic} carrying {@code tenantId} as the tenant header. */
    public static <K, V> ProducerRecord<K, V> record(String topic, @Nullable K key, V value, UUID tenantId) {
        ProducerRecord<K, V> record = new ProducerRecord<>(topic, key, value);
        record.headers().add(TenantHeaders.KAFKA_TENANT_ID, tenantId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** The tenant header of a consumed record, if present and a UUID. */
    public static Optional<UUID> read(Headers headers) {
        Header header = headers.lastHeader(TenantHeaders.KAFKA_TENANT_ID);
        if (header == null || header.value() == null) {
            return Optional.empty();
        }
        String raw = new String(header.value(), StandardCharsets.UTF_8);
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed {} header '{}'", TenantHeaders.KAFKA_TENANT_ID, raw);
            return Optional.empty();
        }
    }
}
