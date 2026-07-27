package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code inventory.events.v1} scrap facts into shrinkage GL postings (odoo-parity D2,
 * issue #1043).
 *
 * <p>Same reliability contract as {@link SettlementEventsListener}: idempotent via {@code
 * processed_events} in the ingest transaction, transient DB errors and posting failures propagate
 * unwrapped for container retry / DLQ (ADR-0044 §4), malformed payloads logged and marked
 * processed so a poison record never blocks the partition. Only {@code inventory.scrap.posted}
 * events are handled; the topic's other (high-volume) fact types are ignored without recording
 * their eventIds.
 *
 * <p><b>Null/zero-cost skip (spec D4 scope 4):</b> the fact's {@code unitCost} is the ADR-0048
 * interim latest-receipt snapshot and is {@code null} when no receipt cost exists
 * ({@code costSource = NONE}). A scrap without a positive cost has no determinate GL value, so no
 * journal entry is posted: the event is logged at WARN (observable skip, greppable as
 * {@code "Skipping uncosted scrap fact"}) and marked processed — record-and-skip, the module's
 * established convention for unpostable payloads (mirrors the malformed-payload path here and in
 * {@link SettlementEventsListener}). Odoo-parity J3 upgrades the cost source; recovery for skipped
 * scraps is a manual journal entry, exactly like any other unpostable event in this module.
 *
 * <p>Posting itself (idempotent on scrapId via posting key, period-gated, accounts resolved
 * through the {@code INVENTORY_SHRINKAGE} posting category) lives in
 * {@link InventoryShrinkagePostingService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final InventoryShrinkagePostingService shrinkagePostingService;

    @KafkaListener(
            topics = "${pos.accounting.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "pos-accounting-inventory-events")
    @Transactional
    public void onInventoryEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable inventory event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!ScrapPostedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring inventory event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping scrap event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate scrap event eventId={}", eventId);
            return;
        }

        // Only deserialization/validation failures are terminal for this record — skip and mark
        // processed so a malformed payload does not poison the partition. Anything thrown by
        // posting (transient DB errors, period gate, unexpected failures) propagates unwrapped
        // for container retry / DLQ.
        ScrapPostedV1 fact;
        try {
            fact = objectMapper.treeToValue(envelope.path("payload"), ScrapPostedV1.class);
        } catch (Exception e) {
            log.warn("Skipping malformed scrap event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        // ADR-0048 interim cost is nullable (costSource NONE) — record-and-skip, never post a
        // zero/indeterminate JE. Marked processed so the record never poison-loops.
        BigDecimal unitCost = fact.unitCost();
        if (unitCost == null || unitCost.signum() <= 0) {
            log.warn(
                    "Skipping uncosted scrap fact — no shrinkage JE posted | eventId={} | scrapId={} | sku={} "
                            + "| quantity={} | reasonCode={} | costSource={} | unitCost={}",
                    eventId,
                    fact.scrapId(),
                    fact.sku(),
                    fact.quantity(),
                    fact.reasonCode(),
                    fact.costSource(),
                    unitCost);
            markProcessed(eventId);
            return;
        }

        shrinkagePostingService.postShrinkage(fact);
        markProcessed(eventId);
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }
}
