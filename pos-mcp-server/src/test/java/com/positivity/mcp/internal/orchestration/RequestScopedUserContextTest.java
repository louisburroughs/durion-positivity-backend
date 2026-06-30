package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.service.CurrentUserContext;
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
}
