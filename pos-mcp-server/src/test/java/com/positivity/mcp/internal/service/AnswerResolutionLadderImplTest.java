package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.internal.service.AnswerResolutionLadder.LadderResult;
import com.positivity.mcp.internal.service.AnswerResolutionLadder.Rung;
import com.positivity.mcp.service.CurrentUserContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnswerResolutionLadderImpl — rung 3/4 fallback")
class AnswerResolutionLadderImplTest {

    @Mock
    private ScreenLinkResolver screenLinkResolver;

    @Mock
    private RequestScopedUserContext requestScopedUserContext;

    private AnswerResolutionLadderImpl ladder() {
        return new AnswerResolutionLadderImpl(screenLinkResolver, requestScopedUserContext);
    }

    private CurrentUserContext userWith(Set<String> permissions) {
        return new CurrentUserContext(
                "user",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "ROLE_ADMIN",
                Set.of(),
                Set.of(),
                permissions);
    }

    @Test
    @DisplayName("a resolved screen yields a DEEP_LINK carrying its title and url")
    void deepLinkWhenScreenResolves() {
        when(requestScopedUserContext.current()).thenReturn(Optional.of(userWith(Set.of("workorder:workorder:view"))));
        when(screenLinkResolver.resolve(any(), nullable(String.class), any(), any()))
                .thenReturn(Optional.of(new ScreenLink("workorders.list", "Work Orders", "/workorders", 0.8)));

        LadderResult result = ladder().resolveFallback("how many workorders are open");

        assertThat(result.rung()).isEqualTo(Rung.DEEP_LINK);
        assertThat(result.screenUrl()).isEqualTo("/workorders");
        assertThat(result.text()).contains("Work Orders").contains("/workorders");
    }

    @Test
    @DisplayName("no resolvable screen yields an honest HANDOFF")
    void handoffWhenNoScreen() {
        when(requestScopedUserContext.current()).thenReturn(Optional.empty());
        when(screenLinkResolver.resolve(any(), nullable(String.class), any(), any()))
                .thenReturn(Optional.empty());

        LadderResult result = ladder().resolveFallback("something unanswerable");

        assertThat(result.rung()).isEqualTo(Rung.HANDOFF);
        assertThat(result.screenUrl()).isNull();
        assertThat(result.text()).isEqualTo(AnswerResolutionLadderImpl.HANDOFF_MESSAGE);
    }

    @Test
    @DisplayName("caller permissions from the request context are passed to the screen resolver")
    void passesCallerPermissions() {
        Set<String> perms = Set.of("workorder:workorder:view", "invoice:invoice:view");
        when(requestScopedUserContext.current()).thenReturn(Optional.of(userWith(perms)));
        when(screenLinkResolver.resolve(any(), nullable(String.class), eq(perms), any()))
                .thenReturn(Optional.empty());

        LadderResult result = ladder().resolveFallback("q");

        assertThat(result.rung()).isEqualTo(Rung.HANDOFF); // resolver returned empty for these perms
    }
}
