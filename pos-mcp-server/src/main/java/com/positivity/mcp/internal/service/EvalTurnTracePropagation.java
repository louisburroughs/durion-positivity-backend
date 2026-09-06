package com.positivity.mcp.internal.service;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Hooks;

/**
 * Carries the active eval turn across Reactor thread hops (#1850).
 *
 * <p>{@link AlphaEvalTurnTraceRecorder} binds the turn to a thread. The blocking chat path runs a
 * whole turn on one servlet thread, so that is enough; a streamed turn executes its tool calls on
 * {@code boundedElastic} and completes on whichever thread delivers the terminal signal, so every
 * {@code recordToolCall} and {@code recordAnswerSource} from those threads previously found no turn
 * and was dropped. Registering the recorder's ThreadLocal with Micrometer's {@code ContextRegistry}
 * and enabling Reactor's automatic context propagation restores it on the threads that handle each
 * signal.
 *
 * <p><b>The hook is JVM-wide and not selective</b>, which is why this is scoped to the alpha profile
 * with tracing enabled — the same condition the recorder carries — and never runs in a normal
 * deployment. Two consequences worth stating rather than discovering:
 *
 * <ul>
 *   <li>Once on, Reactor propagates <em>every</em> registered {@code ThreadLocalAccessor}, not just
 *       this one — Micrometer's observation scope and Spring Security's context are both registered
 *       by ServiceLoader. So on alpha, reactive code runs under a context regime production does not
 *       have. Alpha is where the eval gate measures behaviour, so that is a real caveat, accepted
 *       because a trace of the wrong thread's work is worth less than the difference.
 *   <li>{@link #disable()} removes the accessor and asks Reactor to turn the hook back off, but a
 *       context restart within one JVM cannot un-ring anything another component enabled.
 * </ul>
 *
 * <p>Lives beside the recorder rather than in {@code internal.config}, where registration components
 * usually sit: it needs the recorder, and {@code config} already depends on {@code service}, so the
 * other direction closes a package cycle ArchUnit rejects.
 */
@Component
@Profile("alpha")
@ConditionalOnProperty(name = "mcp.eval.turn-trace.enabled", havingValue = "true")
public class EvalTurnTracePropagation {

    /** Context key for the recorder's active-turn ThreadLocal. */
    static final String TURN_KEY = "mcp.eval.turn";

    private static final Logger LOGGER = LoggerFactory.getLogger(EvalTurnTracePropagation.class);

    private final AlphaEvalTurnTraceRecorder recorder;

    public EvalTurnTracePropagation(@NonNull AlphaEvalTurnTraceRecorder recorder) {
        this.recorder = recorder;
    }

    @PostConstruct
    void enable() {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(
                        TURN_KEY, recorder::currentTurnHandle, recorder::bindTurn, recorder::unbindTurn);
        Hooks.enableAutomaticContextPropagation();
        LOGGER.info("Eval turn traces now follow Reactor thread hops (#1850); key={}", TURN_KEY);
    }

    @PreDestroy
    void disable() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(TURN_KEY);
        // Undo the JVM-wide switch too, so a context restart does not leave it on for whatever
        // starts next. Reactor no-ops if something else has since enabled it.
        Hooks.disableAutomaticContextPropagation();
    }
}
