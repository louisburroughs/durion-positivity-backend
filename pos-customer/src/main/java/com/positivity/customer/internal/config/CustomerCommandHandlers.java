package com.positivity.customer.internal.config;

import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SuppressionReason;
import com.positivity.customer.service.SegmentService;
import com.positivity.customer.service.SuppressionService;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The transactional halves of {@link CustomerCommandListener}'s command handling.
 *
 * <h2>Why these are their own bean</h2>
 *
 * Both methods used to sit on the listener and be called on {@code this} from its
 * {@code @KafkaListener} method, which is not transactional. Spring's transaction advice is
 * proxy-based, so those self-calls bypassed it and the {@code @Transactional} annotations below were
 * never applied — which is precisely what {@link #handleSegmentResolveRequested}'s note requires:
 * the reply fact must be written to the outbox atomically with the read that produced it, or a
 * requester can be told about a resolution that was never recorded. Living in a separate bean means
 * the calls cross the proxy and each command is handled as one unit.
 *
 * <p>The listener's third branch, {@code handleOutboxReplayRequested}, deliberately stays where it
 * is: it declares no transaction and needs none, since it only queues existing outbox rows for
 * re-publication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerCommandHandlers {

    private final SegmentService segmentService;
    private final SuppressionService suppressionService;

    /**
     * Resolve a segment and reply with {@code customer.segment.resolved}.
     *
     * <p>Runs in its own transaction so the reply fact is written to the outbox atomically with
     * the read that produced it. A malformed or unknown request is dropped rather than retried:
     * neither can be fixed by redelivery, and a requester waiting on a reply will time out and
     * ask again.
     */
    @Transactional
    public void handleSegmentResolveRequested(JsonNode root) {
        JsonNode payload = root.path("payload");
        UUID requestId = uuidOrNull(payload, "requestId");
        UUID segmentId = uuidOrNull(payload, "segmentId");
        if (requestId == null || segmentId == null) {
            log.warn("Ignoring segment-resolve command missing requestId or segmentId");
            return;
        }
        try {
            segmentService
                    .resolveAndPublish(requestId, segmentId)
                    .ifPresent(count ->
                            log.info("Resolved segment {} for request {}: {} party(ies)", segmentId, requestId, count));
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Segment {} could not be resolved for request {}: {}", segmentId, requestId, e.getMessage());
        }
    }

    /**
     * Apply a provider-feedback suppression (Story #1150): pos-marketing relays a hard bounce
     * or spam complaint from the shared platform sender, and the address gets hard-blocked
     * here — suppression outranks whatever consent the party may still hold.
     *
     * <p>The raw address exists only inside this command; {@link SuppressionService#add} stores
     * a normalized hash plus a masked hint. Malformed commands are dropped, not retried:
     * redelivery cannot repair a missing address or an unknown enum value.
     */
    @Transactional
    public void handleSuppressionAddRequested(JsonNode root) {
        JsonNode payload = root.path("payload");
        String address = payload.path("address").stringValue(null);
        MarketingChannel channel = enumOrNull(MarketingChannel.class, payload, "channel");
        SuppressionReason reason = enumOrNull(SuppressionReason.class, payload, "reason");
        if (address == null || address.isBlank() || channel == null || reason == null) {
            log.warn("Ignoring suppression-add command missing address, channel, or reason");
            return;
        }
        UUID partyId = uuidOrNull(payload, "partyId");
        var entry = suppressionService.add(
                new AddSuppressionRequest(channel, address, partyId, reason, ConsentChangeSource.SYSTEM));
        log.info(
                "Provider feedback suppressed {} address for party {} (reason {}, suppressionId {})",
                channel,
                partyId,
                reason,
                entry.suppressionId());
    }

    private static <E extends Enum<E>> @Nullable E enumOrNull(Class<E> type, JsonNode node, String field) {
        String value = node.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID uuidOrNull(JsonNode node, String field) {
        String value = node.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
