package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.dto.ProductFactReplayResultDto;
import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
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
 * <h2>One bounded page per invocation, chained to convergence (#1537 F5)</h2>
 *
 * The sibling domains' replay commands are window-bounded ({@code since}/{@code until} over an
 * outbox table) and their {@code OutboxReplayService} can requeue an entire window in one call
 * because outbox rows are cheap to select and hand off. pos-catalog's replay primitive is
 * different in kind: it is cursor/page based ({@code afterId} + {@code limit}, capped at {@code
 * MAX_LIMIT} facts per call) over current-state tables that can hold tens of thousands of rows —
 * exactly the shape the REST {@code /replay} endpoints already use, one bounded call per page.
 *
 * <p>Each {@code onCommand} invocation still calls each targeted replay service's {@code
 * replayPage} <em>exactly once</em> per scope (bounded to {@link #MAX_REPLAY_LIMIT} facts) — that
 * part of the original design is unchanged and load-bearing: a loop inside one invocation would run
 * inside one consumer-poll cycle and one transaction for as long as the catalog has pages left,
 * risking poll-timeout rebalances for a catalog with tens of thousands of products. What changed is
 * what happens when a page is <em>incomplete</em> ({@code result.complete()==false}): this listener
 * now publishes exactly one follow-up {@code catalog.outbox.replay-requested} command back onto its
 * own {@code catalog.commands.v1} topic, carrying {@code afterProductId} (or the sibling cursor
 * field for the scope that didn't complete) set to {@code result.nextAfterId()}, the SAME {@code
 * since} and {@code scope} the original command carried, the same {@code limit}, and {@code
 * continuation} incremented by one. That message is picked up by a later poll cycle as an ordinary
 * command and repeats the process. Every single invocation's cost is therefore still identical
 * regardless of catalog size — the loop moved from "inside one call" to "across a chain of calls" —
 * but the chain as a whole now walks every row matching the filter, in id order, until a page comes
 * back complete.
 *
 * <p><b>What this replaces:</b> before #1537 F5, {@code replayPage} orders by product id with an
 * {@code updatedSince} filter and no persisted cursor between commands, so a single command always
 * re-emitted a fixed id-ordered prefix of at most {@link #MAX_REPLAY_LIMIT} rows — not the actually
 * drifted rows. A product sorted past that prefix by id was excluded from that window's repair, and
 * then excluded from <em>every later</em> repair too, because a later reconciliation window's {@code
 * since} moves forward past the row's {@code updatedAt}. Worse, {@link
 * com.positivity.catalog.internal.service.InventoryManifestListener} — or, for the catalog replica,
 * {@code CatalogManifestListener} in pos-inventory — evaluates a given window exactly once, so the
 * drift alarm for that window fired a single time and then went quiet forever while the replica
 * stayed permanently wrong for the excluded rows: silent divergence with a green signal. The javadoc
 * here previously claimed "the residual gap is caught by the next manifest and repaired
 * incrementally" — that claim was false; the next manifest is a disjoint, later window and does not
 * re-check the rows this window missed. The continuation chain above replaces that false guarantee
 * with a real one, bounded by {@link #MAX_CONTINUATIONS}: see that constant's javadoc for what
 * happens when the bound is reached and why it is chosen where it is.
 *
 * <p><b>What "convergence" means here precisely:</b> a replay chain repairs the <em>replica's
 * current state</em>, not a specific historical manifest window. Every fact this listener re-emits
 * carries a fresh {@code eventId} timestamped now, so it is recorded by the owner's own
 * accounting under the window containing "now" — it can never retroactively satisfy the original
 * drifted window's event count, and this listener does not attempt that. What it does guarantee is
 * that, given enough continuations, every row with {@code updatedAt >= since} — the complete
 * candidate set for that window's repair, not merely the first {@link #MAX_REPLAY_LIMIT} of them by
 * id — gets re-emitted and applied through each consumer's normal (version-gated, never-regressing)
 * apply path, which is what actually needed to be true for the replica to be correct again.
 *
 * <h2>{@code since} / {@code until}</h2>
 *
 * {@code payload.since} maps directly onto the replay primitives' {@code updatedSince} filter.
 * {@code payload.until} has no equivalent: the cursor-by-id replay primitive has no upper-timestamp
 * bound, only a resumption cursor ({@code afterId}), and the repository query behind it
 * ({@code ProductRepository.findForReplay} and its {@code Service}/{@code SupplierArticleCode}
 * siblings) takes no upper-bound parameter to express one — so {@code until} cannot be enforced
 * here without changing that primitive. {@code until} is accepted and logged for audit visibility
 * but not used to filter.
 *
 * <p>This is not free of interaction with the page/continuation budget, and this javadoc previously
 * claimed over-replay "cannot cause harm" — that overstated it. A row updated after {@code until}
 * still matches {@code updatedSince} and consumes a slot in some page of the chain exactly like a
 * genuinely drifted row would, because the query has no way to exclude it. Applying it is harmless
 * to replica correctness (the version-gated apply path never regresses a consumer that already holds
 * something newer), but it is not free: in a catalog under heavy concurrent write traffic, rows
 * updated after {@code until} can occupy enough of the {@link #MAX_CONTINUATIONS}-page budget that a
 * chain exhausts its bound before it reaches every row that was actually part of the drifted
 * window — the two claims on that budget are not independent. Operators reading continuation-
 * exhaustion log lines (see {@link #MAX_CONTINUATIONS}) should treat a chain that exhausted its
 * bound during a period of high write volume as a signal to re-run the replay with a narrower {@code
 * since}, not as proof the original window's drift is unrepairable.
 *
 * <h2>Scope</h2>
 *
 * {@code payload.scope} is optional and, when present, must be one of {@code PRODUCT}, {@code
 * SERVICE}, or {@code SUPPLIER_ARTICLE_CODE} (case-insensitive) to target one replay service.
 * Omitted or blank runs all three — still each individually bounded to one page, and each
 * continuing (if incomplete) as its own independent chain with an explicit {@code scope}: a
 * continuation is always scoped, even when the command that started the chain was not, because only
 * one cursor field is meaningful to resume from at a time.
 *
 * <p>An unrecognized scope is treated as a malformed command: logged and dropped, nothing replayed.
 *
 * <h2>Per-scope failure isolation (#1537 S1)</h2>
 *
 * {@code onCommand} is deliberately <em>not</em> {@code @Transactional}: each replay service's
 * {@code replayPage} already carries its own {@code @Transactional} (REQUIRED), and letting
 * {@code onCommand} join that transaction — as it previously did — meant a non-transient {@code
 * DataAccessException} inside one scope's {@code replayPage} would mark the shared transaction
 * rollback-only; the outer broad catch here would swallow the exception, but the subsequent commit
 * of that shared transaction would then throw {@code UnexpectedRollbackException}, which escaped
 * this listener entirely and drove the message to the DLQ after retries — the opposite of the
 * "malformed/permanent failures are logged and dropped" intent stated below. Removing {@code
 * @Transactional} here means each {@code replayPage} call commits or rolls back independently, so
 * one scope's non-transient failure cannot poison another's transaction.
 *
 * <p>Each {@code replayXxx} helper below also now catches {@link DataAccessException} (excluding
 * {@link TransientDataAccessException}, which is rethrown so the container can retry) around its own
 * {@code replayPage} call: a non-transient failure in one scope is logged and skipped, and the
 * remaining scopes in the same command still run, instead of one scope's exception unwinding past
 * the others and silently discarding their work.
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

    /** Dotted command-type this listener publishes on a continuation (mirrors the inbound form). */
    private static final String REPLAY_COMMAND_TYPE = "catalog.outbox.replay-requested";

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

    /**
     * Bound on how many times one replay chain may continue itself (#1537 F5). Each continuation
     * still emits at most {@link #MAX_REPLAY_LIMIT} facts, so this caps a single chain at {@code
     * MAX_CONTINUATIONS * MAX_REPLAY_LIMIT} = 200,000 facts per scope before it stops and logs
     * exhaustion rather than continuing forever.
     *
     * <p>Chosen against two failure modes at once. Too low, and a legitimate bulk load or PRICAT
     * import — this repo's docs put that at "tens of thousands of rows" — would exhaust the chain
     * before finishing, silently leaving the replica exactly as broken as the original bug (just
     * with a higher row count before giving up). Too high (or unbounded), and a defect that leaves
     * the resumption cursor unable to advance — a repository bug, a clock skew making {@code since}
     * always match, anything that makes {@code result.complete()} never become true — turns into an
     * unbounded command chain hammering the broker forever, which is precisely the risk the original
     * single-page design existed to prevent. 200 continuations covers roughly 4-8x the documented
     * realistic drift scale with room to spare, while still guaranteeing any runaway chain stops,
     * loudly, within 200 hops — a bounded, observable operational cost even if it recurs, not a
     * silent unbounded one.
     */
    static final int MAX_CONTINUATIONS = 200;

    private final ObjectMapper objectMapper;
    private final ProductFactReplayService productFactReplayService;
    private final ServiceFactReplayService serviceFactReplayService;
    private final SupplierArticleCodeReplayService supplierArticleCodeReplayService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${pos.catalog.kafka.commands-topic:catalog.commands.v1}")
    private String commandsTopic;

    @KafkaListener(
            topics = "${pos.catalog.kafka.commands-topic:catalog.commands.v1}",
            groupId = "${pos.catalog.kafka.commands-consumer-group:pos-catalog-commands}")
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
        int continuation = Math.max(payload.path("continuation").intValue(0), 0);
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
            replayProduct(payload, since, until, limit, continuation);
        }
        if (scope == null || SCOPE_SERVICE.equals(scope)) {
            replayService(payload, since, until, limit, continuation);
        }
        if (scope == null || SCOPE_SUPPLIER_ARTICLE_CODE.equals(scope)) {
            replaySupplierArticleCode(payload, since, until, limit, continuation);
        }
    }

    private void replayProduct(
            @NonNull JsonNode payload, @Nullable Instant since, @Nullable Instant until, int limit, int continuation) {
        UUID afterId = parseUuid(payload, "afterProductId");
        try {
            ProductFactReplayResultDto result = productFactReplayService.replayPage(afterId, since, limit);
            log.info(
                    "Catalog replay command processed scope=PRODUCT since={} afterId={} limit={} emitted={}"
                            + " complete={} nextAfterId={} continuation={}",
                    since,
                    afterId,
                    limit,
                    result.emitted(),
                    result.complete(),
                    result.nextAfterId(),
                    continuation);
            if (!result.complete() && result.nextAfterId() != null) {
                publishContinuation(SCOPE_PRODUCT, since, until, limit, continuation, result.nextAfterId());
            }
        } catch (CatalogBusinessRuleException e) {
            // Publication disabled — a permanent condition for this attempt, not a defect; skip
            // this scope and let the other scopes (if any) still be attempted.
            log.warn("Skipping product replay: {}", e.getMessage());
        } catch (TransientDataAccessException e) {
            throw e; // let the outer catch rethrow for container retry/DLQ (ADR-0044 §4)
        } catch (DataAccessException e) {
            // Non-transient failure for this scope only (#1537 S1): log and let sibling scopes in
            // this command still run, instead of one scope's exception discarding all of them.
            log.error("Product replay failed (non-transient); continuing with other scopes", e);
        }
    }

    private void replayService(
            @NonNull JsonNode payload, @Nullable Instant since, @Nullable Instant until, int limit, int continuation) {
        UUID afterId = parseUuid(payload, "afterServiceId");
        try {
            ServiceFactReplayResultDto result = serviceFactReplayService.replayPage(afterId, since, limit);
            log.info(
                    "Catalog replay command processed scope=SERVICE since={} afterId={} limit={} emitted={}"
                            + " complete={} nextAfterId={} continuation={}",
                    since,
                    afterId,
                    limit,
                    result.emitted(),
                    result.complete(),
                    result.nextAfterId(),
                    continuation);
            if (!result.complete() && result.nextAfterId() != null) {
                publishContinuation(SCOPE_SERVICE, since, until, limit, continuation, result.nextAfterId());
            }
        } catch (CatalogBusinessRuleException e) {
            log.warn("Skipping service replay: {}", e.getMessage());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("Service replay failed (non-transient); continuing with other scopes", e);
        }
    }

    private void replaySupplierArticleCode(
            @NonNull JsonNode payload, @Nullable Instant since, @Nullable Instant until, int limit, int continuation) {
        UUID afterId = parseUuid(payload, "afterSupplierArticleCodeId");
        try {
            SupplierArticleCodeReplayResultDto result =
                    supplierArticleCodeReplayService.replayPage(afterId, since, limit);
            log.info(
                    "Catalog replay command processed scope=SUPPLIER_ARTICLE_CODE since={} afterId={} limit={}"
                            + " emitted={} complete={} nextAfterId={} continuation={}",
                    since,
                    afterId,
                    limit,
                    result.emitted(),
                    result.complete(),
                    result.nextAfterId(),
                    continuation);
            if (!result.complete() && result.nextAfterId() != null) {
                publishContinuation(
                        SCOPE_SUPPLIER_ARTICLE_CODE, since, until, limit, continuation, result.nextAfterId());
            }
        } catch (CatalogBusinessRuleException e) {
            log.warn("Skipping supplier-article-code replay: {}", e.getMessage());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("Supplier-article-code replay failed (non-transient); continuing with other scopes", e);
        }
    }

    /**
     * Publishes the follow-up command that resumes an incomplete page (#1537 F5). Carries the SAME
     * {@code since}, {@code until}, and {@code limit} as the command that produced {@code
     * nextAfterId}, an explicit {@code scope} (a continuation is never unscoped — see class javadoc
     * "Scope"), and {@code continuation} incremented by one so {@link #MAX_CONTINUATIONS} can be
     * enforced across the whole chain.
     *
     * <p>A failed publish here is swallowed, not propagated — matching {@code
     * CatalogManifestListener}'s existing convention for this same topic: the chain simply stops:
     * no exception here should fail this listener or poison the partition over what is, at worst, a
     * still-incomplete repair that a fresh drift detection can retry from scratch.
     */
    private void publishContinuation(
            @NonNull String scope,
            @Nullable Instant since,
            @Nullable Instant until,
            int limit,
            int continuation,
            @NonNull UUID nextAfterId) {
        int nextContinuation = continuation + 1;
        if (nextContinuation > MAX_CONTINUATIONS) {
            log.warn(
                    "Catalog replay continuation chain exhausted: scope={} since={} continuationsUsed={}"
                            + " maxContinuations={} cursor={} — repair is incomplete past this cursor; rerun"
                            + " the replay (e.g. with a narrower since) to continue it",
                    scope,
                    since,
                    continuation,
                    MAX_CONTINUATIONS,
                    nextAfterId);
            return;
        }
        try {
            ReplayContinuationCommand.Payload payload = new ReplayContinuationCommand.Payload(
                    since == null ? null : since.toString(),
                    until == null ? null : until.toString(),
                    scope,
                    SCOPE_PRODUCT.equals(scope) ? nextAfterId.toString() : null,
                    SCOPE_SERVICE.equals(scope) ? nextAfterId.toString() : null,
                    SCOPE_SUPPLIER_ARTICLE_CODE.equals(scope) ? nextAfterId.toString() : null,
                    limit,
                    nextContinuation);
            String command =
                    objectMapper.writeValueAsString(new ReplayContinuationCommand(REPLAY_COMMAND_TYPE, payload));
            String key = since == null ? scope : scope + ":" + since;
            kafkaTemplate.send(commandsTopic, key, command);
        } catch (Exception e) {
            log.warn(
                    "Failed to publish replay continuation scope={} since={} continuation={} cursor={}",
                    scope,
                    since,
                    nextContinuation,
                    nextAfterId,
                    e);
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

    /** Command envelope this listener publishes to continue an incomplete replay page (#1537 F5). */
    record ReplayContinuationCommand(
            @NonNull String commandType, @NonNull Payload payload) {
        record Payload(
                @Nullable String since,
                @Nullable String until,
                @NonNull String scope,
                @Nullable String afterProductId,
                @Nullable String afterServiceId,
                @Nullable String afterSupplierArticleCodeId,
                int limit,
                int continuation) {}
    }
}
