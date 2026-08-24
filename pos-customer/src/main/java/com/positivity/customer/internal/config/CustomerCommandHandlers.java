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
import tools.jackson.databind.JsonNode;

/**
 * The transactional halves of {@link CustomerCommandListener}'s command handling.
 *
 * <h2>Why these are their own bean</h2>
 *
 * Both methods used to sit on the listener and be called on {@code this} from its
 * {@code @KafkaListener} method, which is not transactional. Spring's transaction advice is
 * proxy-based, so those self-calls bypassed it and the {@code @Transactional} annotations they used
 * to carry were never applied. Living in a separate bean means the dispatch crosses the proxy.
 *
 * <h2>Why neither method declares {@code @Transactional} itself</h2>
 *
 * Both delegate to a service method that is already {@code @Transactional} —
 * {@code SegmentServiceImpl.resolveAndPublish} and {@code SuppressionServiceImpl.add} — so the unit
 * these handlers need is the one those boundaries already provide, including
 * {@code resolveAndPublish} writing its reply fact to the outbox atomically with the read that
 * produced it.
 *
 * <p>Adding an outer transaction would not merely be redundant, it would be wrong. A participating
 * inner transaction that rolls back marks the shared transaction rollback-only, so the outer commit
 * throws {@link org.springframework.transaction.UnexpectedRollbackException} — escaping past
 * {@link #handleSegmentResolveRequested}'s catch, which exists precisely so a malformed or unknown
 * request is dropped rather than retried. The catch runs before the commit and cannot see it. So
 * the boundary stays where the work is, and these methods stay plain dispatch.
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
     * <p>{@code resolveAndPublish} is transactional, so the reply fact is written to the outbox
     * atomically with the read that produced it. A malformed or unknown request is dropped rather
     * than retried: neither can be fixed by redelivery, and a requester waiting on a reply will
     * time out and ask again. See the class note on why this method adds no transaction of its own —
     * one would turn that drop into a thrown {@code UnexpectedRollbackException}.
     */
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
     *
     * <p>{@code SuppressionServiceImpl.add} is transactional; see the class note on why this method
     * adds no transaction of its own.
     */
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
