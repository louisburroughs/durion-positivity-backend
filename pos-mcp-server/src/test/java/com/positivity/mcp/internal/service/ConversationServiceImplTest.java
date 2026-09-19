package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.ConversationProperties;
import com.positivity.mcp.internal.dto.AppendMessageRequest;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.dto.ConversationDetail;
import com.positivity.mcp.internal.dto.ConversationPolicy;
import com.positivity.mcp.internal.dto.ConversationSummary;
import com.positivity.mcp.internal.dto.CreateConversationRequest;
import com.positivity.mcp.internal.dto.UpdateConversationRequest;
import com.positivity.mcp.internal.entity.McpConversation;
import com.positivity.mcp.internal.entity.McpMessage;
import com.positivity.mcp.internal.enums.ConversationMessageOrigin;
import com.positivity.mcp.internal.enums.ConversationMessageRole;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.mcp.internal.service.ConversationStore.ConversationWithMessages;
import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link ConversationServiceImpl} (#2073): title validation, the 256&nbsp;KB
 * appended-turn limit, content-derived-from-blocks, DTO mapping (order/preview/pinned preserved
 * from the store, never resorted here), and chat-memory eviction on delete/deleteAll.
 *
 * <p>List ordering and the 200-row cap are enforced by {@link ConversationStore}'s repository
 * query, not here (proved by {@code ConversationPersistenceIT}); this class only proves that
 * {@link ConversationServiceImpl#list()} does not reorder what the (mocked) store returns.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-7000-8000-000000000901");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-18T09:55:00Z");
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-09-18T09:56:12Z");

    @Mock
    private ConversationStore store;

    @Mock
    private ObjectProvider<AgentOrchestrationService> agentOrchestrationServiceProvider;

    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationServiceImpl(
                store, new ConversationProperties(30, Duration.ofHours(1)), agentOrchestrationServiceProvider);
        lenient().when(store.deserializeBlocks(any(UUID.class), any())).thenReturn(List.of());
        lenient().when(store.serializeBlocks(anyList())).thenReturn("[]");
        authenticateAs(OWNER);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -- create: title validation and pass-through -------------------------------------------

    @Test
    @DisplayName("create: an omitted title passes null through to the store")
    void create_titleOmitted_passesNullToStore() {
        when(store.create(OWNER, null)).thenReturn(conversation("New conversation", false, null));

        ConversationDetail detail = service.create(new CreateConversationRequest(null));

        verify(store).create(OWNER, null);
        assertThat(detail.messages()).isEmpty();
    }

    @Test
    @DisplayName("create: a title is trimmed before it reaches the store")
    void create_titleProvided_trimsBeforeStoreCall() {
        when(store.create(OWNER, "Hello World")).thenReturn(conversation("Hello World", false, null));

        service.create(new CreateConversationRequest("  Hello World  "));

        verify(store).create(OWNER, "Hello World");
    }

    @Test
    @DisplayName("create: a title blank after trimming is rejected before the store is touched")
    void create_blankTitleAfterTrim_throwsValidation() {
        assertThatThrownBy(() -> service.create(new CreateConversationRequest("   ")))
                .isInstanceOf(ConstraintViolationException.class);

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("create: a title over 120 characters after trimming is rejected")
    void create_titleTooLong_throwsValidation() {
        String tooLong = "x".repeat(McpConversation.TITLE_MAX_LENGTH + 1);

        assertThatThrownBy(() -> service.create(new CreateConversationRequest(tooLong)))
                .isInstanceOf(ConstraintViolationException.class);

        verifyNoInteractions(store);
    }

    // -- get -----------------------------------------------------------------------------------

    // A malformed id can no longer reach the service: get/update/delete/appendMessage take a UUID
    // path variable (ADR-0027), so Spring's own binder rejects a non-UUID id before dispatch —
    // proved at the HTTP layer by McpConversationControllerTest, not here.

    @Test
    @DisplayName("get: an id the store does not return for this owner is 404")
    void get_unknownOwnedId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(store.findWithMessages(id, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("get: messages are returned in the store's order, with preview and pinned preserved")
    void get_ownedId_mapsMessagesInStoreOrderWithPreviewAndPinned() {
        UUID id = UUID.randomUUID();
        McpConversation conv = conversation("Roster", true, "You have 26 mechanics, all ACTIVE.");
        McpMessage first = message(ConversationMessageRole.USER, "how many mechanics");
        McpMessage second = message(ConversationMessageRole.ASSISTANT, "You have 26 mechanics, all ACTIVE.");
        when(store.findWithMessages(id, OWNER))
                .thenReturn(Optional.of(new ConversationWithMessages(conv, List.of(first, second))));

        ConversationDetail detail = service.get(id);

        assertThat(detail.pinned()).isTrue();
        assertThat(detail.preview()).isEqualTo("You have 26 mechanics, all ACTIVE.");
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.messages().get(0).id()).isEqualTo(first.getId());
        assertThat(detail.messages().get(1).id()).isEqualTo(second.getId());
    }

    // -- update --------------------------------------------------------------------------------

    @Test
    @DisplayName("update: a body with neither field set passes nulls through (no-op, still 200)")
    void update_noFieldsSet_passesNullsThrough() {
        UUID id = UUID.randomUUID();
        McpConversation conv = conversation("Unchanged", false, null);
        when(store.update(id, OWNER, null, null))
                .thenReturn(Optional.of(new ConversationWithMessages(conv, List.of())));

        ConversationDetail detail = service.update(id, new UpdateConversationRequest(null, null));

        verify(store).update(id, OWNER, null, null);
        assertThat(detail.title()).isEqualTo("Unchanged");
    }

    @Test
    @DisplayName("update: a title is trimmed before it reaches the store")
    void update_titleProvided_trimsBeforeStoreCall() {
        UUID id = UUID.randomUUID();
        McpConversation conv = conversation("Renamed", false, null);
        when(store.update(id, OWNER, "Renamed", true))
                .thenReturn(Optional.of(new ConversationWithMessages(conv, List.of())));

        service.update(id, new UpdateConversationRequest("  Renamed  ", true));

        verify(store).update(id, OWNER, "Renamed", true);
    }

    @Test
    @DisplayName("update: an id the store does not own is 404")
    void update_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(store.update(id, OWNER, null, true)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new UpdateConversationRequest(null, true)))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    // -- delete / deleteAll: chat-memory eviction -----------------------------------------------

    @Test
    @DisplayName("delete: an owned conversation is deleted and its chat memory evicted")
    void delete_ownedId_evictsMemoryAfterDelete() {
        UUID id = UUID.randomUUID();
        when(store.delete(id, OWNER)).thenReturn(true);
        AgentOrchestrationService orchestration = mock(AgentOrchestrationService.class);
        when(agentOrchestrationServiceProvider.getIfAvailable()).thenReturn(orchestration);

        service.delete(id);

        verify(orchestration).evictConversation(id.toString());
    }

    @Test
    @DisplayName("delete: an id the store does not own is 404 and evicts nothing")
    void delete_unknownId_throwsNotFound_noEviction() {
        UUID id = UUID.randomUUID();
        when(store.delete(id, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConversationNotFoundException.class);

        verifyNoInteractions(agentOrchestrationServiceProvider);
    }

    @Test
    @DisplayName("delete: no orchestration wired (e.g. non-alpha profile) does not throw")
    void delete_noOrchestrationWired_doesNotThrow() {
        UUID id = UUID.randomUUID();
        when(store.delete(id, OWNER)).thenReturn(true);
        when(agentOrchestrationServiceProvider.getIfAvailable()).thenReturn(null);

        service.delete(id);
    }

    @Test
    @DisplayName("deleteAll: every deleted id's chat memory is evicted")
    void deleteAll_evictsEachDeletedId() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(store.deleteAll(OWNER)).thenReturn(List.of(id1, id2));
        AgentOrchestrationService orchestration = mock(AgentOrchestrationService.class);
        when(agentOrchestrationServiceProvider.getIfAvailable()).thenReturn(orchestration);

        service.deleteAll();

        verify(orchestration).evictConversation(id1.toString());
        verify(orchestration).evictConversation(id2.toString());
    }

    // -- appendMessage ---------------------------------------------------------------------------

    @Test
    @DisplayName("appendMessage: content is derived from blocks when the content field is blank")
    void appendMessage_contentDerivedFromBlocksWhenContentBlank() {
        UUID id = UUID.randomUUID();
        McpMessage stored = message(ConversationMessageRole.USER, "hi there");
        when(store.appendClientMessage(eq(id), eq(OWNER), eq(ConversationMessageRole.USER), any(), any()))
                .thenReturn(Optional.of(stored));

        service.appendMessage(
                id, new AppendMessageRequest("user", List.of(new ChatBlock.MarkdownBlock("hi there")), null));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(store)
                .appendClientMessage(
                        eq(id), eq(OWNER), eq(ConversationMessageRole.USER), contentCaptor.capture(), any());
        assertThat(contentCaptor.getValue()).isEqualTo("hi there");
    }

    @Test
    @DisplayName("appendMessage: blank content and no blocks is rejected before the store is touched")
    void appendMessage_blankContentAndEmptyBlocks_throwsValidation() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.appendMessage(id, new AppendMessageRequest("user", List.of(), null)))
                .isInstanceOf(ConstraintViolationException.class);

        verify(store, never()).appendClientMessage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("appendMessage: a turn over 256 KB serialized is rejected before the store is touched")
    void appendMessage_exceeds256KbLimit_throwsValidation() {
        UUID id = UUID.randomUUID();
        String bigContent = "x".repeat(ConversationServiceImpl.MAX_APPENDED_TURN_BYTES + 10);

        assertThatThrownBy(() -> service.appendMessage(id, new AppendMessageRequest("user", List.of(), bigContent)))
                .isInstanceOf(ConstraintViolationException.class);

        verify(store, never()).appendClientMessage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("appendMessage: an id the store does not own is 404")
    void appendMessage_unknownConversation_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(store.appendClientMessage(eq(id), eq(OWNER), eq(ConversationMessageRole.USER), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.appendMessage(id, new AppendMessageRequest("user", List.of(), "hi")))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("appendMessage: an invalid role is rejected before the store is touched")
    void appendMessage_invalidRole_throwsValidation() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.appendMessage(id, new AppendMessageRequest("bogus", List.of(), "hi")))
                .isInstanceOf(ConstraintViolationException.class);

        verifyNoInteractions(store);
    }

    // -- feedback (#2075) --------------------------------------------------------------------------

    @Test
    @DisplayName("setFeedback: the owner and the request's values pass through to the store")
    void setFeedback_passesOwnerAndValuesThrough() {
        UUID id = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(store.setFeedback(id, messageId, OWNER, "not_helpful", "incorrect", "wrong total"))
                .thenReturn(true);

        service.setFeedback(
                id,
                messageId,
                new com.positivity.mcp.internal.dto.MessageFeedbackRequest("not_helpful", "incorrect", "wrong total"));

        verify(store).setFeedback(id, messageId, OWNER, "not_helpful", "incorrect", "wrong total");
    }

    @Test
    @DisplayName("setFeedback: the store returning false is MessageNotFoundException")
    void setFeedback_storeReturnsFalse_throwsMessageNotFound() {
        UUID id = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(store.setFeedback(eq(id), eq(messageId), eq(OWNER), any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.setFeedback(
                        id,
                        messageId,
                        new com.positivity.mcp.internal.dto.MessageFeedbackRequest("helpful", null, null)))
                .isInstanceOf(com.positivity.mcp.internal.exception.MessageNotFoundException.class);
    }

    @Test
    @DisplayName("clearFeedback: the owner passes through to the store")
    void clearFeedback_passesOwnerThrough() {
        UUID id = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(store.clearFeedback(id, messageId, OWNER)).thenReturn(true);

        service.clearFeedback(id, messageId);

        verify(store).clearFeedback(id, messageId, OWNER);
    }

    @Test
    @DisplayName("clearFeedback: the store returning false is MessageNotFoundException")
    void clearFeedback_storeReturnsFalse_throwsMessageNotFound() {
        UUID id = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(store.clearFeedback(id, messageId, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> service.clearFeedback(id, messageId))
                .isInstanceOf(com.positivity.mcp.internal.exception.MessageNotFoundException.class);
    }

    @Test
    @DisplayName("get: an unrated assistant message maps feedback to null")
    void get_unratedMessage_feedbackIsNull() {
        UUID id = UUID.randomUUID();
        McpConversation conv = conversation("Roster", false, null);
        McpMessage assistantMessage = message(ConversationMessageRole.ASSISTANT, "answer");
        when(store.findWithMessages(id, OWNER))
                .thenReturn(Optional.of(new ConversationWithMessages(conv, List.of(assistantMessage))));

        ConversationDetail detail = service.get(id);

        assertThat(detail.messages().get(0).feedback()).isNull();
    }

    @Test
    @DisplayName("get: a rated assistant message maps feedback with rating/reason/comment/ratedAt")
    void get_ratedMessage_feedbackMapped() {
        UUID id = UUID.randomUUID();
        McpConversation conv = conversation("Roster", false, null);
        McpMessage assistantMessage = message(ConversationMessageRole.ASSISTANT, "answer");
        assistantMessage.setFeedbackRating("helpful");
        assistantMessage.setFeedbackReason("other");
        assistantMessage.setFeedbackComment("great answer");
        assistantMessage.setFeedbackAt(UPDATED_AT);
        when(store.findWithMessages(id, OWNER))
                .thenReturn(Optional.of(new ConversationWithMessages(conv, List.of(assistantMessage))));

        ConversationDetail detail = service.get(id);

        var feedback = detail.messages().get(0).feedback();
        assertThat(feedback).isNotNull();
        assertThat(feedback.rating()).isEqualTo("helpful");
        assertThat(feedback.reason()).isEqualTo("other");
        assertThat(feedback.comment()).isEqualTo("great answer");
        assertThat(feedback.ratedAt()).isEqualTo(UPDATED_AT.toInstant());
    }

    // -- policy / list -----------------------------------------------------------------------------

    @Test
    @DisplayName("policy: reflects the configured retention days; pinned is always exempt")
    void policy_returnsConfiguredRetentionDays() {
        assertThat(service.policy()).isEqualTo(new ConversationPolicy(30, true));
    }

    @Test
    @DisplayName("list: the store's order (pinned first, updatedAt desc) is preserved, not resorted")
    void list_preservesStoreOrderAndMapsFields() {
        McpConversation pinned = conversation("Pinned convo", true, "latest");
        McpConversation unpinned = conversation("Unpinned convo", false, null);
        when(store.rail(OWNER)).thenReturn(List.of(pinned, unpinned));

        List<ConversationSummary> summaries = service.list();

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).id()).isEqualTo(pinned.getId());
        assertThat(summaries.get(0).pinned()).isTrue();
        assertThat(summaries.get(1).id()).isEqualTo(unpinned.getId());
        assertThat(summaries.get(1).pinned()).isFalse();
    }

    private static McpConversation conversation(String title, boolean pinned, String preview) {
        McpConversation conversation = new McpConversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOwnerUserId(OWNER);
        conversation.setTitle(title);
        conversation.setTitleUserSet(true);
        conversation.setPreview(preview);
        conversation.setPinned(pinned);
        conversation.setCreatedAt(CREATED_AT);
        conversation.setUpdatedAt(UPDATED_AT);
        return conversation;
    }

    private static McpMessage message(ConversationMessageRole role, String content) {
        McpMessage message = new McpMessage();
        message.setId(UUID.randomUUID());
        message.setConversationId(UUID.randomUUID());
        message.setRole(role);
        message.setOrigin(ConversationMessageOrigin.CLIENT);
        message.setContent(content);
        message.setBlocks("[]");
        message.setCreatedAt(CREATED_AT);
        return message;
    }

    private static void authenticateAs(UUID userId) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("test-user", "n/a", "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID,
                userId,
                GatewaySecurityConstants.DETAIL_USERNAME,
                "test-user"));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
