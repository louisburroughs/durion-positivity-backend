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
 * <p>Deliberately scoped to the alpha profile with tracing enabled, the same condition the recorder
 * carries: {@link Hooks#enableAutomaticContextPropagation()} is a JVM-wide switch, so it is
 * installed only where the traces it exists for are actually collected, never in a normal
 * deployment.
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
        ContextRegistry.getInstance().registerThreadLocalAccessor(TURN_KEY, recorder.activeTurn);
        Hooks.enableAutomaticContextPropagation();
        LOGGER.info("Eval turn traces now follow Reactor thread hops (#1850); key={}", TURN_KEY);
    }

    @PreDestroy
    void disable() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(TURN_KEY);
    }
}
