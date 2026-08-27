package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.dto.ProductFactReplayResultDto;
import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.service.ProductFactReplayService;
import com.positivity.catalog.service.ServiceFactReplayService;
import com.positivity.catalog.service.SupplierArticleCodeReplayService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for {@code catalog.commands.v1} (ADR-0044 §4, issue #1537).
 *
 * <p>Modeled on {@code LocationCommandListener}'s minimal replay-only shape: this is pos-catalog's
 * first command listener, closing the gap left by #1023 where {@code CatalogManifestListener} (in
 * pos-inventory) could detect replica drift but had no command topic to ask for repair.
 *
 * <p>Supported command types:
 *
 * <ul>
 *   <li>{@code catalog.outbox.replay-requested} — consumer-initiated drift repair and replica
 *       bootstrap: re-emits current-state facts through {@link ProductFactReplayService}, {@link
 *       ServiceFactReplayService}, and {@link SupplierArticleCodeReplayService} — the same paged
 *       primitives the {@code /replay} REST endpoints already expose. Re-emitted facts are
 *       indistinguishable from live ones, so every consumer applies them through its normal path
 *       and normal stale guard.
 * </ul>
 *
 * <h2>One page per command, not a driven loop</h2>
 *
 * The sibling domains' replay commands are window-bounded ({@code since}/{@code until} over an
 * outbox table) and their {@code OutboxReplayService} can requeue an entire window in one call
 * because outbox rows are cheap to select and hand off. pos-catalog's replay primitive is
 * different in kind: it is cursor/page based ({@code afterId} + {@code limit}, capped at {@code
 * MAX_LIMIT} facts per call) over current-state tables that can hold tens of thousands of rows —
 * exactly the shape the REST {@code /replay} endpoints already use, one bounded call per page,
 * with the caller driving the loop.
 *
 * <p>This listener keeps that same contract instead of inventing a second one: each Kafka command
 * invocation calls each targeted replay service's {@code replayPage} <em>exactly once</em> (bounded
 * to {@link #MAX_REPLAY_LIMIT} facts) and logs the result, including {@code nextAfterId}. It does
 * NOT loop internally to drain a whole replay from a single command. A loop here would run inside
 * one Kafka listener invocation — and by extension one consumer-poll cycle and one DB transaction —
 * for as long as the catalog has pages left; for a catalog with tens of thousands of products that
 * is unbounded work blocking a single partition's processing, risking poll-timeout driven
 * rebalances and a transaction held open far longer than any other command this listener (or its
 * siblings) ever runs. Bounding each command to one page keeps every invocation's cost identical
 * regardless of catalog size, exactly like {@code MAX_LIMIT} already bounds one REST call.
 *
 * <p>Continuing a large replay is therefore the same operation as continuing a large REST replay:
 * issue another command carrying the {@code nextAfterId} this listener logged. The manifest-driven
 * auto-repair path ({@code CatalogManifestListener} in pos-inventory) is not expected to need this
 * often — it fires per reconciliation window, which is normally a modest number of changes — but if
 * a window's drift exceeds one page, this cycle repairs only part of it; the residual gap is caught
 * by the next manifest and repaired incrementally rather than being force-fit into one unbounded
 * call.
 *
 * <h2>{@code since} / {@code until}</h2>
 *
 * {@code payload.since} maps directly onto the replay primitives' {@code updatedSince} filter.
 * {@code payload.until} has no equivalent: the cursor-by-id primitive has no upper-timestamp bound,
 * only a resumption cursor, so enforcing an upper bound would require scanning past it and
 * discarding rows — work with no payoff. {@code until} is therefore accepted and logged for audit
 * visibility but not used to filter. This cannot cause harm: replaying a fact updated after {@code
 * until} is still just a fact the consumer either already holds (and skips, via its stale guard) or
 * did not (and now correctly does) — an over-inclusive window can only be mildly redundant, never
 * regressive.
 *
 * <h2>Scope</h2>
 *
 * {@code payload.scope} is optional and, when present, must be one of {@code PRODUCT}, {@code
 * SERVICE}, or {@code SUPPLIER_ARTICLE_CODE} (case-insensitive) to target one replay service.
 * Omitted or blank runs all three — still each individually bounded to one page. An unrecognized
 * scope is treated as a malformed command: logged and dropped, nothing replayed.
 *
 * <h2>No {@code processed_events} idempotency for this command</h2>
 *
 * Unlike {@code InventoryCommandListener}'s state-mutating commands (pick-list operations), replay
 * is idempotent by construction — the same reason {@code LocationCommandListener} and {@code
 * InventoryCommandListener}'s own {@code outbox.replay-requested} handling need no dedupe: every
 * emission carries a fresh {@code eventId} that consumers dedupe on for redelivery, and consumers
 * apply a fact only when it is newer than what they hold, so redelivering — or redundantly
 * re-issuing — the same replay command twice is harmless, not merely tolerated. pos-catalog does
 * have a {@code ProcessedEvent}/{@code processed_events} table and repository (used elsewhere for
 * consumed-fact idempotency), but adding commandId-keyed dedupe here would guard against a failure
 * mode that cannot occur, so this listener does not use it — matching {@code
 * LocationCommandListener}'s shape rather than {@code InventoryCommandListener}'s dedupe-required
 * commands.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class CatalogCommandListener {

    /** Canonical dotted name normalized to command-type form: CATALOG_OUTBOX_REPLAY_REQUESTED. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "CATALOG_OUTBOX_REPLAY_REQUESTED";

    private static final String SCOPE_PRODUCT = "PRODUCT";
    private static final String SCOPE_SERVICE = "SERVICE";
    private static final String SCOPE_SUPPLIER_ARTICLE_CODE = "SUPPLIER_ARTICLE_CODE";

    /**
     * Bound on facts emitted by one command. Mirrors {@code ProductFactReplayServiceImpl.MAX_LIMIT}
     * (and its {@code Service}/{@code SupplierArticleCode} siblings, which share the same value) —
     * duplicated here, consistent with how those three implementations already each define their
     * own copy, rather than reaching from {@code internal.config} into {@code internal.service}
     * implementation classes for a constant. The replay services re-clamp to their own {@code
     * MAX_LIMIT} regardless, so this bound is a documented default, not the sole enforcement.
     */
    static final int MAX_REPLAY_LIMIT = 1000;

    private final ObjectMapper objectMapper;
    private final ProductFactReplayService productFactReplayService;
    private final ServiceFactReplayService serviceFactReplayService;
    private final SupplierArticleCodeReplayService supplierArticleCodeReplayService;

    @KafkaListener(
            topics = "${pos.catalog.kafka.commands-topic:catalog.commands.v1}",
            groupId = "${pos.catalog.kafka.commands-consumer-group:pos-catalog-commands}")
    @Transactional
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType: {}", message);
                return;
            }
            // Normalize dotted command names (catalog.outbox.replay-requested) to one form.
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
        } catch (TransientDataAccessException e) {
            // Let the container error handler retry with backoff and route to {topic}.dlq
            // (ADR-0044 §4) — replay is idempotent, so redelivery is harmless.
            throw e;
        } catch (Exception e) {
            // Malformed/unsupported commands are permanent failures: retrying cannot fix them,
            // so log and drop instead of poisoning the partition.
            log.error("Failed to process Kafka command message: {}", message, e);
        }
    }

    private void handleOutboxReplayRequested(@NonNull JsonNode root) {
        JsonNode payload = root.path("payload");
        Instant since = parseInstant(payload, "since");
        Instant until = parseInstant(payload, "until");
        int limit = clampLimit(payload.path("limit").intValue(MAX_REPLAY_LIMIT));
        String rawScope = payload.path("scope").stringValue(null);
        String scope = rawScope == null || rawScope.isBlank() ? null : rawScope.toUpperCase(Locale.ROOT);

        if (scope != null
                && !SCOPE_PRODUCT.equals(scope)
                && !SCOPE_SERVICE.equals(scope)
                && !SCOPE_SUPPLIER_ARTICLE_CODE.equals(scope)) {
            log.warn("Ignoring outbox replay command with unrecognized scope={}: {}", rawScope, root);
            return;
        }
        if (until != null) {
            // Advisory only — see class javadoc "since / until": the cursor-by-id replay primitive
            // has no upper-timestamp bound to enforce it against.
            log.debug("Outbox replay command carried until={} (advisory; not used to filter)", until);
        }

        if (scope == null || SCOPE_PRODUCT.equals(scope)) {
            replayProduct(payload, since, limit);
        }
        if (scope == null || SCOPE_SERVICE.equals(scope)) {
            replayService(payload, since, limit);
        }
        if (scope == null || SCOPE_SUPPLIER_ARTICLE_CODE.equals(scope)) {
            replaySupplierArticleCode(payload, since, limit);
        }
    }

    private void replayProduct(@NonNull JsonNode payload, @Nullable Instant since, int limit) {
        UUID afterId = parseUuid(payload, "afterProductId");
        try {
            ProductFactReplayResultDto result = productFactReplayService.replayPage(afterId, since, limit);
            log.info(
                    "Catalog replay command processed scope=PRODUCT since={} afterId={} limit={} emitted={}"
                            + " complete={} nextAfterId={}",
                    since,
                    afterId,
                    limit,
                    result.emitted(),
                    result.complete(),
                    result.nextAfterId());
        } catch (CatalogBusinessRuleException e) {
            // Publication disabled — a permanent condition for this attempt, not a defect; skip
            // this scope and let the other scopes (if any) still be attempted.
            log.warn("Skipping product replay: {}", e.getMessage());
        }
    }

    private void replayService(@NonNull JsonNode payload, @Nullable Instant since, int limit) {
        UUID afterId = parseUuid(payload, "afterServiceId");
        try {
            ServiceFactReplayResultDto result = serviceFactReplayService.replayPage(afterId, since, limit);
            log.info(
                    "Catalog replay command processed scope=SERVICE since={} afterId={} limit={} emitted={}"
                            + " complete={} nextAfterId={}",
                    since,
                    afterId,
                    limit,
                    result.emitted(),
                    result.complete(),
                    result.nextAfterId());
        } catch (CatalogBusinessRuleException e) {
            log.warn("Skipping service replay: {}", e.getMessage());
        }
    }

    private void replaySupplierArticleCode(@NonNull JsonNode payload, @Nullable Instant since, int limit) {
        UUID afterId = parseUuid(payload, "afterSupplierArticleCodeId");
        try {
            SupplierArticleCodeReplayResultDto result =
                    supplierArticleCodeReplayService.replayPage(afterId, since, limit);
            log.info(
                    "Catalog replay command processed scope=SUPPLIER_ARTICLE_CODE since={} afterId={} limit={}"
                            + " emitted={} complete={} nextAfterId={}",
                    since,
                    afterId,
                    limit,
                    result.emitted(),
                    result.complete(),
                    result.nextAfterId());
        } catch (CatalogBusinessRuleException e) {
            log.warn("Skipping supplier-article-code replay: {}", e.getMessage());
        }
    }

    private static int clampLimit(int requested) {
        return Math.min(Math.max(requested, 1), MAX_REPLAY_LIMIT);
    }

    private @Nullable UUID parseUuid(@Nullable JsonNode node, @NonNull String field) {
        String value = node == null ? null : node.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            log.warn("Malformed UUID {}={}", field, value);
            return null;
        }
    }

    private @Nullable Instant parseInstant(@Nullable JsonNode payloadNode, @NonNull String field) {
        String value = payloadNode == null ? null : payloadNode.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception _) {
            log.warn("Malformed payload.{}={} on outbox replay command", field, value);
            return null;
        }
    }
}
