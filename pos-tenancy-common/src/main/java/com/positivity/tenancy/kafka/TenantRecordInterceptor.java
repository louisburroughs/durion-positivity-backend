package com.positivity.tenancy.kafka;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Binds the producing tenant before each {@code @KafkaListener} invocation and clears it after
 * (ADR-0062 §3, async path), so consumer database work is scoped exactly like a request.
 *
 * <p>A record without the header falls back to the transitional default tenant while producers are
 * being retrofitted (plan WS4); with no default configured and {@code pos.tenancy.enforce} on it is
 * rejected with {@link TenantContextMissingException}, which the container's error handler routes
 * like any other failure (retry, then the dead-letter topic) rather than processing it unscoped.
 * With enforcement off the listener runs unbound and row-level security decides.
 */
public class TenantRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    private static final Logger log = LoggerFactory.getLogger(TenantRecordInterceptor.class);

    private final TenancyProperties properties;

    public TenantRecordInterceptor(TenancyProperties properties) {
        this.properties = properties;
    }

    @Override
    public @Nullable ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        Optional<UUID> tenant = TenantKafkaHeaders.read(record.headers()).or(properties::getDefaultTenantId);
        if (tenant.isEmpty()) {
            if (!properties.isEnforce()) {
                TenantContext.clear();
                return record;
            }
            log.error(
                    "Record topic={} partition={} offset={} carries no tenant header and no default tenant is"
                            + " configured; refusing to process it unscoped",
                    record.topic(),
                    record.partition(),
                    record.offset());
            throw new TenantContextMissingException();
        }
        TenantContext.bind(tenant.get());
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        TenantContext.clear();
    }
}
