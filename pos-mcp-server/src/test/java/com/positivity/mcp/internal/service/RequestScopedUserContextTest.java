package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.scopegraph.ScopeSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 3: request-scoped user-context holder semantics (leakage-prevention primitive). */
class RequestScopedUserContextTest {

    private final RequestScopedUserContext holder = new RequestScopedUserContext();

    @AfterEach
    void cleanup() {
        holder.clear();
    }

    private CurrentUserContext ctx() {
        return new CurrentUserContext(
                "advisor",
                UUID.randomUUID(),
                "ROLE_SERVICE_ADVISOR",
                Set.of("ROLE_SERVICE_ADVISOR"),
                Set.of("AUTHENTICATED"),
                Set.of("workorder:workorder:view"));
    }

    @Test
    @DisplayName("absent by default; present after set; absent after clear")
    void setGetClear() {
        assertThat(holder.current()).isEmpty();
        CurrentUserContext c = ctx();
        holder.set(c);
        assertThat(holder.current()).contains(c);
        holder.clear();
        assertThat(holder.current()).isEmpty();
    }

    @Test
    @DisplayName("ADR-0069: the scope is absent by default, present once recorded, and cleared with the caller")
    void scopeIsPublishedAndClearedWithTheCaller() {
        assertThat(holder.currentScope()).isEmpty();

        holder.set(ctx());
        // A caller alone publishes no scope: mode off, and the simple-chat path, look like this.
        assertThat(holder.currentScope()).isEmpty();

        ScopeSet scope = ScopeSet.empty();
        holder.recordScope(scope);
        assertThat(holder.currentScope()).containsSame(scope);

        holder.clear();
        assertThat(holder.currentScope()).isEmpty();
        assertThat(holder.current()).isEmpty();
    }

    @Test
    @DisplayName("ADR-0069: a scope published on one thread is not visible on another")
    void scopeIsThreadBound() throws Exception {
        holder.recordScope(ScopeSet.empty());
        java.util.concurrent.atomic.AtomicBoolean seen = new java.util.concurrent.atomic.AtomicBoolean(true);

        Thread other = new Thread(() -> seen.set(holder.currentScope().isPresent()));
        other.start();
        other.join();

        assertThat(seen).isFalse();
    }
}
