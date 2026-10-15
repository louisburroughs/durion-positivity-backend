package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import org.jspecify.annotations.NonNull;

/**
 * Everything a closed register session posts (CAP:550 S17, #2513; S18, #2514), shared by the two paths that post one:
 * {@link OrderEventsListener} when the close fact is consumed, and {@link RegisterSessionCloseReprocessor} when a held
 * session is reprocessed from its stored fact (#2594). One sequence, so a session posts the same way on either path.
 *
 * <p>The caller runs it inside one transaction, so the session posts all or nothing: its drawer movements, then its
 * over/short. A session the movement posting held for its currency posts no over/short either; a schema-1 fact has no
 * movements and posts the over/short alone. Once both posted, a schema-2 session in functional currency writes its
 * undeposited-session row (S18), in the same transaction, so the row exists exactly when the 1095 lines its clearing
 * net describes do; a held session writes none, and a session whose row exists writes none again.
 *
 * <p>Not a bean: both callers build it from the same three services, so neither depends on the other.
 */
final class RegisterSessionClosePosting {

    private final RegisterCashMovementPostingService movementPostingService;
    private final RegisterOverShortPostingService overShortPostingService;
    private final UndepositedSessionProjection undepositedSessions;

    RegisterSessionClosePosting(
            RegisterCashMovementPostingService movementPostingService,
            RegisterOverShortPostingService overShortPostingService,
            UndepositedSessionProjection undepositedSessions) {
        this.movementPostingService = movementPostingService;
        this.overShortPostingService = overShortPostingService;
        this.undepositedSessions = undepositedSessions;
    }

    /**
     * Post the closed session, in the caller's transaction.
     *
     * @param fact the session-closed fact
     * @param envelopeEventId the consumed envelope's event id, kept on a currency-held record
     * @param schemaVersion the fact's schema version; below 2 writes no undeposited session
     * @return what the session did, for its ingestion record
     */
    @NonNull
    FactPostingOutcome post(@NonNull RegisterSessionClosedV1 fact, @NonNull String envelopeEventId, int schemaVersion) {
        FactPostingOutcome movements = movementPostingService.postMovements(fact, envelopeEventId);
        if (movements instanceof FactPostingOutcome.CurrencyHeld) {
            return movements;
        }
        FactPostingOutcome outcome =
                FactPostingOutcome.combine(overShortPostingService.postOverShort(fact, envelopeEventId), movements);
        if (!(outcome instanceof FactPostingOutcome.CurrencyHeld)) {
            undepositedSessions.record(fact, schemaVersion);
        }
        return outcome;
    }

    /**
     * The held record's detail for a session refused for a missing mapping (S17 PROPOSED 6, #2594): the resolver's
     * message, the {@code CATEGORY/KEY} the posting named when the message does not already say it (the resolver
     * names only ids for a mapping with no effective row), and the remedy.
     */
    static @NonNull String mappingDetail(@NonNull GLMappingNotConfiguredException missing) {
        String message = String.valueOf(missing.getMessage());
        String reference = missing.getReferenceId();
        boolean named = reference == null || message.contains(reference);
        return message + (named ? "" : " (" + reference + ")")
                + "; nothing posted for the session: configure the mapping, then reprocess";
    }
}
