package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.service.ConversationTurnService.ChatTurnResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link ConversationTurnServiceImpl} (#2073, anvil decisions 8-10, user decision
 * U1): id resolution (new/reused/unknown/ephemeral), that nothing is persisted when the model
 * call throws, that a conversation deleted mid-turn still returns its answer, and the O1 empty-
 * segmentation contract (blocks {@code []} with content always populated).
 */
@ExtendWith(MockitoExtension.class)
class ConversationTurnServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000902");
    private static final CurrentUserContext USER = new CurrentUserContext(
            "test-user", USER_ID, "ROLE_USER", Set.of("ROLE_USER"), Set.of("ROLE_USER"), Set.of("AUTHENTICATED"));

    @Mock
    private AgentOrchestrationService agentOrchestrationService;

    @Mock
    private CurrentUserContextResolver currentUserContextResolver;

    @Mock
    private ConversationStore store;

    private ConversationTurnServiceImpl turnService;

    @BeforeEach
    void setUp() {
        turnService = new ConversationTurnServiceImpl(agentOrchestrationService, currentUserContextResolver, store);
        when(currentUserContextResolver.resolve(any(Authentication.class))).thenReturn(USER);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("test-user", "n/a", "ROLE_USER");
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("null conversationId starts a new conversation; a fresh UUID is returned and persisted")
    void runTurn_nullId_startsNewConversationAndReturnsIds() {
        when(agentOrchestrationService.chat(eq(USER), eq("hi"), any())).thenReturn("answer");
        UUID assistantMessageId = UUID.randomUUID();
        when(store.recordChatTurn(any(), eq(true), eq(USER_ID), eq("hi"), anyList(), eq("answer"), anyList()))
                .thenReturn(Optional.of(assistantMessageId));

        ChatTurnResult result = turnService.runTurn(null, "hi");

        assertThat(ConversationIds.parseCanonical(result.conversationId())).isNotNull();
        assertThat(result.response()).isEqualTo("answer");
        assertThat(result.messageId()).isEqualTo(assistantMessageId);
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(store)
                .recordChatTurn(
                        idCaptor.capture(), eq(true), eq(USER_ID), eq("hi"), anyList(), eq("answer"), anyList());
        assertThat(idCaptor.getValue().toString()).isEqualTo(result.conversationId());
        verify(agentOrchestrationService).chat(USER, "hi", idCaptor.getValue().toString());
    }

    @Test
    @DisplayName("a UUID conversationId owned by the caller is reused, not replaced")
    void runTurn_ownedUuid_reusesConversation() {
        UUID existingId = UUID.randomUUID();
        when(store.isOwned(existingId, USER_ID)).thenReturn(true);
        when(agentOrchestrationService.chat(USER, "hi", existingId.toString())).thenReturn("answer");
        UUID assistantMessageId = UUID.randomUUID();
        when(store.recordChatTurn(
                        existingId,
                        false,
                        USER_ID,
                        "hi",
                        List.of(new ChatBlock.TextBlock("hi")),
                        "answer",
                        List.of(new ChatBlock.MarkdownBlock("answer"))))
                .thenReturn(Optional.of(assistantMessageId));

        ChatTurnResult result = turnService.runTurn(existingId.toString(), "hi");

        assertThat(result.conversationId()).isEqualTo(existingId.toString());
        assertThat(result.messageId()).isEqualTo(assistantMessageId);
    }

    @Test
    @DisplayName("a UUID conversationId not found/owned answers 404 without calling the model or persisting")
    void runTurn_unknownOrOtherOwnerUuid_throwsNotFound_noModelCallNoPersist() {
        UUID otherOwnerId = UUID.randomUUID();
        when(store.isOwned(otherOwnerId, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> turnService.runTurn(otherOwnerId.toString(), "hi"))
                .isInstanceOf(ConversationNotFoundException.class);

        verifyNoInteractions(agentOrchestrationService);
        verify(store, never()).recordChatTurn(any(), anyBoolean(), any(), any(), anyList(), any(), anyList());
    }

    @Test
    @DisplayName("a non-UUID conversationId is the deprecated ephemeral path: memory-only, messageId null")
    void runTurn_nonUuid_ephemeral_messageIdNullNothingPersisted() {
        when(agentOrchestrationService.chat(USER, "hi", "gate-q07")).thenReturn("answer");

        ChatTurnResult result = turnService.runTurn("gate-q07", "hi");

        assertThat(result.conversationId()).isEqualTo("gate-q07");
        assertThat(result.messageId()).isNull();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a model failure (including the 429 rate limit) persists nothing")
    void runTurn_modelException_nothingPersisted() {
        when(agentOrchestrationService.chat(eq(USER), eq("hi"), any()))
                .thenThrow(new RateLimitExceededException("Rate limit exceeded"));

        assertThatThrownBy(() -> turnService.runTurn(null, "hi")).isInstanceOf(RateLimitExceededException.class);

        verify(store, never()).recordChatTurn(any(), anyBoolean(), any(), any(), anyList(), any(), anyList());
    }

    @Test
    @DisplayName("a conversation deleted mid-turn still returns the answer, with messageId null")
    void runTurn_conversationDeletedMidTurn_answerReturnedMessageIdNull() {
        UUID existingId = UUID.randomUUID();
        when(store.isOwned(existingId, USER_ID)).thenReturn(true);
        when(agentOrchestrationService.chat(USER, "hi", existingId.toString())).thenReturn("answer");
        when(store.recordChatTurn(eq(existingId), eq(false), eq(USER_ID), eq("hi"), anyList(), eq("answer"), anyList()))
                .thenReturn(Optional.empty());

        ChatTurnResult result = turnService.runTurn(existingId.toString(), "hi");

        assertThat(result.response()).isEqualTo("answer");
        assertThat(result.conversationId()).isEqualTo(existingId.toString());
        assertThat(result.messageId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("O1: an answer that segments to nothing is persisted as blocks [] with content still populated")
    void runTurn_emptySegmentation_persistedAsEmptyBlocksWithContent() {
        // Same fixture as ChatBlockSegmenterTest/McpChatControllerTest's documented O1 case: a table
        // directly following a list item's first line (no blank line) forms no top-level GFM Table
        // node, so the segmenter's safety net yields an empty list rather than a partial one.
        String nestedTableMarkdown = "- item one\n  | A | B |\n  | --- | --- |\n  | 1 | 2 |";
        when(agentOrchestrationService.chat(eq(USER), eq("hi"), any())).thenReturn(nestedTableMarkdown);
        when(store.recordChatTurn(
                        any(), eq(true), eq(USER_ID), eq("hi"), anyList(), eq(nestedTableMarkdown), anyList()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        ChatTurnResult result = turnService.runTurn(null, "hi");

        assertThat(result.response()).isEqualTo(nestedTableMarkdown);
        assertThat(result.blocks()).isEmpty();
        ArgumentCaptor<List<ChatBlock>> assistantBlocksCaptor = ArgumentCaptor.forClass(List.class);
        verify(store)
                .recordChatTurn(
                        any(),
                        eq(true),
                        eq(USER_ID),
                        eq("hi"),
                        anyList(),
                        eq(nestedTableMarkdown),
                        assistantBlocksCaptor.capture());
        assertThat(assistantBlocksCaptor.getValue()).isEmpty();
    }
}
