package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.EntityDefinition;
import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-0069 §6 row 3 / ADR-0068 spec §2.6, §2.7: the lexicon lookups the {@code lookups} consumer
 * enables. Pure and in memory: the entities a message names by lexicon term or identifier (matched
 * exactly as the scope resolver matches the same lexicon, glossary phrases aside) and, from them, the
 * lexicon's {@code workflow_state}.
 *
 * <p>Built from the lexicon alone, not the graph, so the heuristic tagger (ADR-0068 §2) can hold it
 * without a graph holder and without depending on the build. The matcher is built on first use, so
 * a context that never enforces {@code lookups} pays nothing for it.
 */
@Component
public class LexiconLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexiconLookup.class);

    /** One entity's lexicon workflow state, and the entity and match kind it was read from. */
    public record WorkflowLookup(
            @NonNull String entity,
            @NonNull MatchKind kind,
            @NonNull WorkflowState state) {}

    /** Strongest match first (identifier, exact term, ...), then entity key: deterministic across turns. */
    private static final Comparator<Seed> STRONGEST_FIRST =
            Comparator.comparing(Seed::kind).thenComparing(Seed::entity);

    private final EntityLexicon lexicon;
    private final Map<String, WorkflowState> workflowStates;
    private volatile @Nullable TermMatcher matcher;

    /** Over the lexicon shipped with the module; an unreadable lexicon looks up nothing. */
    public LexiconLookup() {
        this(loadDefault());
    }

    public LexiconLookup(@NonNull EntityLexicon lexicon) {
        this.lexicon = lexicon;
        Map<String, WorkflowState> states = new TreeMap<>();
        for (EntityDefinition entity : lexicon.entities()) {
            if (entity.workflowState() != null) {
                states.put(entity.key(), entity.workflowState());
            }
        }
        this.workflowStates = Map.copyOf(states);
    }

    private static EntityLexicon loadDefault() {
        try {
            return EntityLexiconLoader.loadDefault();
        } catch (EntityLexiconException exception) {
            // The scope-graph loader reports the fault itself; here it costs the lookups only.
            LOGGER.warn("Entity lexicon unavailable for the lexicon lookups: {}", exception.getMessage());
            return new EntityLexicon(Map.of(), List.of(), List.of());
        }
    }

    /** The lexicon this lookup reads. */
    public @NonNull EntityLexicon lexicon() {
        return lexicon;
    }

    /** The entities {@code message} names by lexicon term or identifier, with how each was recognised. */
    public @NonNull List<Seed> seeds(@NonNull String message) {
        return matcher().match(message);
    }

    /**
     * Spec §2.6: the lexicon {@code workflow_state} of the entity {@code message} names, when one is
     * set. Of several such entities the strongest match wins, then the first entity key. Empty when
     * no named entity carries a state, which sends the caller to the phrase match.
     */
    public @NonNull Optional<WorkflowLookup> workflowStateFor(@NonNull String message) {
        return workflowStateFor(message, List.of());
    }

    /**
     * Spec §2.6, §2.7: as {@link #workflowStateFor(String)}, over the entities the message names and
     * the entities the turn's acting {@code entity_<key>} tags seed ({@link MatchKind#TAG}). The
     * lookup contract is entity to workflow state, whichever way the entity was recognised: an
     * {@code ACTION} the model tagged {@code purchase-order} reads {@code CREATING_PO} though the
     * wording names no purchase-order term. A message match outranks a tag seed ({@code TAG} is the
     * weakest kind); a tagged key the lexicon does not know is ignored.
     */
    public @NonNull Optional<WorkflowLookup> workflowStateFor(
            @NonNull String message, @NonNull Collection<String> taggedEntities) {
        if (workflowStates.isEmpty()) {
            return Optional.empty();
        }
        return Stream.concat(
                        seeds(message).stream(), taggedEntities.stream().map(entity -> new Seed(entity, MatchKind.TAG)))
                .filter(seed -> workflowStates.containsKey(seed.entity()))
                .sorted(STRONGEST_FIRST)
                .findFirst()
                .map(seed -> new WorkflowLookup(seed.entity(), seed.kind(), workflowStates.get(seed.entity())));
    }

    private TermMatcher matcher() {
        TermMatcher current = matcher;
        if (current == null) {
            // A racing turn at worst builds it twice; the result is the same.
            current = TermMatcher.of(lexicon);
            matcher = current;
        }
        return current;
    }
}
