package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.service.RolePromptResolver;
import com.positivity.mcp.internal.service.SystemPromptDefaults;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #2415: the T0 fast path skips prompt assembly, so it appends the IDENTIFIER layer itself. */
class SimpleChatFastPathTest {

    @Test
    @DisplayName("T0 prompt carries the default IDENTIFIER layer after the caller context (even for ROLE_ADMIN)")
    void promptCarriesIdentifierLayer() {
        RolePromptResolver resolver = mock(RolePromptResolver.class);
        when(resolver.resolvePrompt(SystemPromptDefaults.MASTER_PROMPT_NAME)).thenReturn("MASTER");
        SharedOrchestrationSupport support =
                new SharedOrchestrationSupport(Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC));
        SimpleChatFastPath fastPath = new SimpleChatFastPath(mock(SimpleChatClassifier.class), resolver, support);
        CurrentUserContext user = new CurrentUserContext(
                "admin", UUID.randomUUID(), "ROLE_ADMIN", Set.of("ROLE_ADMIN"), Set.of(), Set.of());

        String system = fastPath.prompt(user, "hello").getSystemMessage().getText();

        assertThat(system).startsWith("MASTER").endsWith(SystemPromptDefaults.IDENTIFIER_LAYER_TEXT);
        assertThat(system.indexOf("Authenticated user context:"))
                .isLessThan(system.indexOf("Identifier display contract:"));
    }
}
