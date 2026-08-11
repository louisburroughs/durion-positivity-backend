package com.positivity.supplier.internal.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Metadata describing one outbound supplier exchange (architecture doc §6.1).
 *
 * @param exchangeAuditId reference to the persisted exchange-audit row (raw payloads,
 *     timings, outcome — ADR-0050 §7; audit persistence arrives in a later slice)
 * @param correlationId the X-Correlation-Id propagated on the exchange
 * @param protocolFamily wire-format family the exchange used
 * @param protocolVersion norm version the exchange used
 * @param unmappedFields canonical fields the bound norm could not express, recorded per
 *     exchange so nothing is silently dropped (ADR-0051 §4); empty when the norm covered the
 *     full request
 */
public record ExchangeMetadata(
        @NonNull UUID exchangeAuditId,
        @NonNull String correlationId,
        @NonNull ProtocolFamily protocolFamily,
        @NonNull ProtocolVersion protocolVersion,
        @NonNull List<String> unmappedFields) {

    public ExchangeMetadata {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(protocolFamily, "protocolFamily must not be null");
        Objects.requireNonNull(protocolVersion, "protocolVersion must not be null");
        Objects.requireNonNull(unmappedFields, "unmappedFields must not be null");
        unmappedFields = List.copyOf(unmappedFields);
    }
}
