package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.service.CurrentUserContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Default ladder tail. Tries a semantic screen deep link (rung 3), falling back to an honest
 * hand-off (rung 4). Disabled by setting {@code mcp.ladder.enabled=false}, which removes the bean so
 * callers keep their prior behaviour (surfacing the thinking channel).
 */
@Service
@Profile({"!test", "openapi"})
@ConditionalOnProperty(name = "mcp.ladder.enabled", havingValue = "true", matchIfMissing = true)
public class AnswerResolutionLadderImpl implements AnswerResolutionLadder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerResolutionLadderImpl.class);

    static final String HANDOFF_MESSAGE = "I couldn't find a way to answer that from the available tools or screens. "
            + "Try rephrasing, or contact your administrator if you expect this to be supported.";

    private final ScreenLinkResolver screenLinkResolver;

    @Nullable
    private final RequestScopedUserContext requestScopedUserContext;

    public AnswerResolutionLadderImpl(
            @NonNull ScreenLinkResolver screenLinkResolver,
            @Nullable RequestScopedUserContext requestScopedUserContext) {
        this.screenLinkResolver = screenLinkResolver;
        this.requestScopedUserContext = requestScopedUserContext;
    }

    @Override
    public @NonNull LadderResult resolveFallback(@NonNull String userMessage) {
        Set<String> permissions = callerPermissions();
        // No structured entity extraction yet (v1): pass no params, so the screen's url_template
        // resolves to its base path with optional filters dropped.
        Optional<ScreenLink> link = screenLinkResolver.resolve(userMessage, null, permissions, Map.of());
        if (link.isPresent()) {
            ScreenLink resolved = link.get();
            LOGGER.debug("Ladder rung=DEEP_LINK screenKey={} score={}", resolved.screenKey(), resolved.score());
            String text = "I can't compute that directly, but you can view it here — " + resolved.title() + ": "
                    + resolved.url();
            return new LadderResult(text, Rung.DEEP_LINK, resolved.url());
        }
        LOGGER.debug("Ladder rung=HANDOFF (no screen cleared the similarity floor)");
        return new LadderResult(HANDOFF_MESSAGE, Rung.HANDOFF, null);
    }

    private @NonNull Set<String> callerPermissions() {
        if (requestScopedUserContext == null) {
            return Set.of();
        }
        return requestScopedUserContext
                .current()
                .map(CurrentUserContext::permissionCodes)
                .orElseGet(Set::of);
    }
}
