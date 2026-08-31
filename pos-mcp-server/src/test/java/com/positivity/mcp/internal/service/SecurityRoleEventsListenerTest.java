package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Issue #1613, D4: the event tier, which covers what the pull tiers cannot — a persona edited on a
 * role already in the snapshot never misses, so it would otherwise wait for the scheduled re-pull.
 *
 * <p>A poison message must not block the partition, so the malformed cases matter as much as the
 * happy one.
 */
@DisplayName("SecurityRoleEventsListener (#1613)")
class SecurityRoleEventsListenerTest {

    private static final String PERSONA_EVENT = """
            {"eventId":"01960003-0000-7000-8000-000000000001",
             "eventType":"security.role.persona.changed",
             "schemaVersion":1,
             "aggregateId":"00000000-0000-0000-0000-0000000000aa",
             "aggregateVersion":1756640000000,
             "sourceService":"pos-security-service",
             "payload":{"roleId":"00000000-0000-0000-0000-0000000000aa","name":"SHOP_MANAGER",
                        "description":"Branch operations lead","personaTitle":"shop manager",
                        "personaFocus":"branch operations and queue control",
                        "personaTone":"decisive and operational","mcpPersonaRank":35,
                        "mcpPersonaEligible":true}}
            """;

    private RolePersonaSnapshotHolder holder;
    private SystemPromptRepository repository;
    private SecurityRoleEventsListener listener;

    @BeforeEach
    void setUp() {
        holder = TestSnapshots.emptyHolder();
        repository = Mockito.mock(SystemPromptRepository.class);
        Mockito.when(repository.findByName(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.save(Mockito.any(SystemPrompt.class))).thenAnswer(i -> i.getArgument(0));
        listener = new SecurityRoleEventsListener(
                new ObjectMapper(), TestSnapshots.unreachableRefresher(repository, holder));
    }

    @Test
    @DisplayName("applies the persona carried by the event without calling back upstream")
    void appliesPersonaFromEvent() {
        // The refresher here has no reachable upstream on purpose: the fact carries current state,
        // so applying it must not depend on a follow-up fetch.
        listener.onSecurityEvent(PERSONA_EVENT);

        assertThat(holder.get().rankedAuthorities()).containsExactly("ROLE_SHOP_MANAGER");
        assertThat(holder.get().personaText("ROLE_SHOP_MANAGER"))
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("you are assisting a shop manager");
    }

    @Test
    @DisplayName("applying the same event twice is the same as applying it once")
    void isIdempotent() {
        // Why no processed-event table: redelivery and retry converge because the fact is state,
        // not a delta.
        listener.onSecurityEvent(PERSONA_EVENT);
        listener.onSecurityEvent(PERSONA_EVENT);

        assertThat(holder.get().rankedAuthorities()).containsExactly("ROLE_SHOP_MANAGER");
    }

    @Test
    @DisplayName("ignores other event types on the shared security topic")
    void ignoresOtherEventTypes() {
        listener.onSecurityEvent("""
                {"eventType":"security.user.created","payload":{"username":"jane"}}
                """);

        assertThat(holder.get().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("skips an unparsable message rather than blocking the partition")
    void skipsUnparsableMessage() {
        assertThatCode(() -> listener.onSecurityEvent("not json at all")).doesNotThrowAnyException();
        assertThat(holder.get().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("skips a persona event whose payload is malformed")
    void skipsMalformedPayload() {
        // name is required by the payload's own invariant; a violation must not become a poison
        // message that stalls every later event on the partition.
        assertThatCode(() -> listener.onSecurityEvent("""
                        {"eventType":"security.role.persona.changed",
                         "payload":{"roleId":"00000000-0000-0000-0000-0000000000aa","name":""}}
                        """)).doesNotThrowAnyException();
        assertThat(holder.get().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an ineligible role is recorded as excluded rather than given a persona")
    void ineligibleRoleIsRecordedAsExcluded() {
        listener.onSecurityEvent("""
                {"eventType":"security.role.persona.changed",
                 "payload":{"roleId":"00000000-0000-0000-0000-0000000000bb","name":"CUSTOMER",
                            "mcpPersonaEligible":false}}
                """);

        assertThat(holder.get().isIneligible("ROLE_CUSTOMER")).isTrue();
        assertThat(holder.get().rankedAuthorities()).isEmpty();
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(SystemPrompt.class));
    }
}
