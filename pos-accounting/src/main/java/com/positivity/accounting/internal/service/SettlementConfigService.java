package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtPaymentSettlementConfig;
import com.positivity.accounting.internal.enums.FeeRepresentation;
import com.positivity.accounting.internal.enums.MatchReferenceField;
import com.positivity.accounting.internal.repository.ExtPaymentSettlementConfigRepository;
import com.positivity.domainevents.payment.SettlementProviderConfigV1;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the accounting-local upsert of the {@code ext_payment_settlement_config}
 * replica from the compacted {@code payment.settlement-config.v1} topic (ADR-0044
 * §3, story F1c, decision D-9). The repository access and enum mapping live here in
 * the service layer (ADR-0011/0026) — {@link SettlementConfigEventsListener} only
 * adapts the Kafka envelope to a call on this service (mirroring how {@link
 * SettlementEventsListener} delegates to {@link
 * com.positivity.accounting.service.SettlementReconciliationService}).
 *
 * <p>Each record is the full current config for its {@code providerCode} (compaction
 * key, last-writer-wins), so the operation is a plain upsert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementConfigService {

    private final ExtPaymentSettlementConfigRepository configRepository;

    /**
     * Upsert the settlement-config replica for one provider from its compacted-topic
     * payload. Runs in the caller's transaction.
     */
    @Transactional
    public void upsert(@NonNull SettlementProviderConfigV1 payload) {
        configRepository.save(ExtPaymentSettlementConfig.builder()
                .providerCode(payload.providerCode())
                .eventId(payload.eventId())
                .providerName(payload.providerName())
                .matchReferenceField(MatchReferenceField.valueOf(
                        payload.matchReferenceField().name()))
                .amountTolerance(payload.amountTolerance())
                .feeRepresentation(
                        FeeRepresentation.valueOf(payload.feeRepresentation().name()))
                .build());
        log.info("Upserted settlement config replica providerCode={}", payload.providerCode());
    }
}
