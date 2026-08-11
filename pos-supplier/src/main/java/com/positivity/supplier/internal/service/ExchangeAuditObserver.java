package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.spi.ExchangeContext;
import com.positivity.supplier.internal.spi.ExchangeObserver;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link ExchangeObserver} that records outbound attempts (ADR-0050 §7). Delegates the write itself to
 * {@link ExchangeAuditWriter} and owns exactly one thing: making sure an audit failure cannot reach the caller.
 *
 * <p>Two corrections to what this header used to claim, both introduced by changes made after it was written:
 *
 * <ul>
 *   <li>It said this class "persists one row per outbound attempt". It no longer persists anything — the write
 *       moved to {@link ExchangeAuditWriter} because a self-invocation was silently disabling its
 *       {@code REQUIRES_NEW}.
 *   <li>It said this class replaces the no-op observer "by being the only {@code ExchangeObserver} bean in the
 *       context". It is not: {@code ExchangeObserverConfig} registers the no-op unconditionally, and this class
 *       wins by being {@code @Primary}. The previous arrangement relied on {@code @ConditionalOnMissingBean},
 *       which for two component-scanned classes resolves by scan order.
 * </ul>
 *
 * <h2>Redaction is the writer's obligation, and the reason it exists at all</h2>
 *
 * {@link ExchangeContext} carries <strong>raw wire documents</strong> — the base client is
 * format-agnostic and cannot know which element is sensitive. {@link ExchangeAuditWriter} applies the binding's
 * {@link PayloadCaptureLevel} through {@link PayloadRedactor} before anything is persisted. Getting
 * this wrong is unrecoverable in the sense that matters: a credential written into a 400-day store
 * cannot be un-written.
 *
 * <h2>Own transaction, and failures never propagate — one of THREE different semantics here</h2>
 *
 * pos-supplier has three write-alongside-work concerns with deliberately different failure behaviour.
 * They are easy to mistake for each other, so all three are named at all three sites:
 *
 * <ol>
 *   <li><strong>This class plus {@link ExchangeAuditWriter}</strong> — {@code REQUIRES_NEW} on the writer,
 *       failures swallowed here. Live vendor traffic must not fail because the audit sink is down. The split
 *       across two beans is not decoration: a self-invocation kept the {@code REQUIRES_NEW} from ever
 *       applying, and both halves of this guarantee were false while looking correct.
 *   <li>{@link SupplierScheduleCoordinator#runPage} — {@code REQUIRED}, rolls back with its page. A
 *       checkpoint committing independently silently skips a window.
 *   <li>{@link AuditAccessRecorder} — {@code MANDATORY}, fails the read. ADR-0050 §7 makes the access
 *       record a precondition of access, so an unrecordable read must return nothing.
 * </ol>
 *
 * <p>Do not unify them, and do not copy this class's pattern to either of the others.
 *
 * This class owns exactly one thing: making sure an audit failure cannot reach the caller. ADR-0050 §7
 * wants the trail complete, but a successful vendor exchange must not be reported as failed because the
 * audit sink was unavailable. A failure to audit is logged at ERROR — loudly, because a silent gap in a
 * commercial audit trail is worse than a noisy log.
 *
 * <p>{@code @Primary} rather than relying on {@code @ConditionalOnMissingBean} in
 * {@code ExchangeObserverConfig}: that condition evaluates against beans registered so far, which for two
 * scanned classes is component-scan ordering, and Spring documents it as unreliable outside
 * auto-configuration. It happened to work. Which observer receives every vendor payload should not depend on
 * classpath iteration order, so the precedence is now declared.
 */
@Component
@Primary
public class ExchangeAuditObserver implements ExchangeObserver {

    private static final Logger log = LoggerFactory.getLogger(ExchangeAuditObserver.class);

    /** Actor recorded for exchanges with no security context, e.g. scheduler runs (ADR-0018). */
    public static final String SYSTEM_ACTOR = "system:supplier-exchange";

    private final ExchangeAuditWriter writer;

    public ExchangeAuditObserver(@NonNull ExchangeAuditWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void onExchange(@NonNull ExchangeContext context) {
        Objects.requireNonNull(context, "context must not be null");
        try {
            // Through the injected bean, NEVER a method on this. A self-invocation would bypass the proxy
            // and silently disable the writer's REQUIRES_NEW -- which is exactly the defect this split
            // exists to fix. See ExchangeAuditWriter for what that cost.
            writer.write(context);
        } catch (RuntimeException ex) {
            // Deliberately swallowed: the exchange itself succeeded or failed on its own merits and
            // must be reported as such. Logged at ERROR because a gap in the trail needs attention.
            log.error(
                    "Failed to persist exchange audit for vendorProfileId={} capability={} correlationId={};"
                            + " the exchange result is unaffected but this row is MISSING from the audit trail",
                    context.vendorProfileId(),
                    context.capability(),
                    context.correlationId(),
                    ex);
        }
    }
}
