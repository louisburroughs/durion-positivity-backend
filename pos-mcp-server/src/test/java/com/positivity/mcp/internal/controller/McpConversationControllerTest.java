package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.dto.AppendMessageRequest;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.dto.ConversationDetail;
import com.positivity.mcp.internal.dto.ConversationMessage;
import com.positivity.mcp.internal.dto.ConversationPolicy;
import com.positivity.mcp.internal.dto.ConversationSummary;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.ConversationService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice tests for {@link McpConversationController} (#2073).
 *
 * <p>{@link ConversationService} is fully mocked: ownership/tenancy resolution is the service's
 * job (proved by {@code ConversationPersistenceIT}); this class proves the HTTP contract — status
 * codes, JSON shape, the 404-not-403 exception mapping, request validation, and the
 * {@code mcp:chat:execute} permission gate.
 */
@WebMvcTest(McpConversationController.class)
@Import(WebCommonErrorAutoConfiguration.class)
@ActiveProfiles("test")
class McpConversationControllerTest {

    private static final String BASE = "/v1/mcp/conversations";
    private static final Instant CREATED_AT = Instant.parse("2026-09-18T09:55:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-18T09:56:12Z");
    private static final UUID CONVERSATION_ID = UUID.fromString("0198f2b1-6c2a-7c3e-8f00-1234567890ab");
    private static final String CONVERSATION_ID_STR = "0198f2b1-6c2a-7c3e-8f00-1234567890ab";
    private static final UUID MESSAGE_ID = UUID.fromString("0198f2b1-7a10-7b21-9c00-abcdef123456");
    /** Syntactically valid UUID that no test ever configures the mock to own (the 404 cases). */
    private static final UUID UNKNOWN_ID = UUID.fromString("0198f2b1-0000-7000-8000-000000000000");

    private static final String UNKNOWN_ID_STR = "0198f2b1-0000-7000-8000-000000000000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService conversationService;

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("GET /v1/mcp/conversations returns 200 with the caller's summaries")
    void list_returns200WithSummaries() throws Exception {
        when(conversationService.list())
                .thenReturn(List.of(new ConversationSummary(
                        CONVERSATION_ID,
                        "Mechanic roster count",
                        "You have 26 mechanics, all ACTIVE.",
                        CREATED_AT,
                        UPDATED_AT,
                        true)));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(CONVERSATION_ID_STR))
                .andExpect(jsonPath("$[0].title").value("Mechanic roster count"))
                .andExpect(jsonPath("$[0].pinned").value(true));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("GET /v1/mcp/conversations/policy returns the retention policy")
    void policy_returns200WithRetentionPolicy() throws Exception {
        when(conversationService.policy()).thenReturn(new ConversationPolicy(30, true));

        mockMvc.perform(get(BASE + "/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionDays").value(30))
                .andExpect(jsonPath("$.pinnedExempt").value(true));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/conversations returns 201 with the created conversation")
    void create_returns201WithConversation() throws Exception {
        when(conversationService.create(any()))
                .thenReturn(new ConversationDetail(
                        CONVERSATION_ID, "New conversation", null, CREATED_AT, UPDATED_AT, false, List.of()));

        mockMvc.perform(post(BASE)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONVERSATION_ID_STR))
                .andExpect(jsonPath("$.title").value("New conversation"))
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages.length()").value(0));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/conversations: a title the service rejects (blank after trim) returns 400")
    void create_serviceRejectsTitle_returns400() throws Exception {
        when(conversationService.create(any()))
                .thenThrow(new jakarta.validation.ConstraintViolationException(
                        "title must be 1..120 characters after trimming", java.util.Set.of()));

        mockMvc.perform(post(BASE)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("GET /v1/mcp/conversations/{id} returns 200 with messages")
    void get_ownedId_returns200WithMessages() throws Exception {
        ConversationMessage message = new ConversationMessage(
                MESSAGE_ID,
                "assistant",
                UPDATED_AT,
                List.of(new ChatBlock.MarkdownBlock("You have 26 mechanics, all ACTIVE.")),
                "You have 26 mechanics, all ACTIVE.");
        when(conversationService.get(CONVERSATION_ID))
                .thenReturn(new ConversationDetail(
                        CONVERSATION_ID,
                        "Mechanic roster count",
                        "You have 26 mechanics, all ACTIVE.",
                        CREATED_AT,
                        UPDATED_AT,
                        false,
                        List.of(message)));

        mockMvc.perform(get(BASE + "/" + CONVERSATION_ID_STR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("assistant"))
                .andExpect(jsonPath("$.messages[0].blocks[0].kind").value("markdown"))
                .andExpect(jsonPath("$.messages[0].content").value("You have 26 mechanics, all ACTIVE."));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("GET /v1/mcp/conversations/{id}: unknown or another subject's id returns 404, never 403")
    void get_unknownId_returns404ConversationNotFound() throws Exception {
        when(conversationService.get(UNKNOWN_ID))
                .thenThrow(new ConversationNotFoundException("Conversation not found: " + UNKNOWN_ID_STR));

        mockMvc.perform(get(BASE + "/" + UNKNOWN_ID_STR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("GET /v1/mcp/conversations/{id}: id that is not a valid UUID returns 400")
    void get_malformedId_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/does-not-exist"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("PATCH /v1/mcp/conversations/{id} returns 200 with the updated conversation")
    void update_returns200() throws Exception {
        when(conversationService.update(eq(CONVERSATION_ID), any()))
                .thenReturn(new ConversationDetail(
                        CONVERSATION_ID, "Renamed", null, CREATED_AT, UPDATED_AT, true, List.of()));

        mockMvc.perform(patch(BASE + "/" + CONVERSATION_ID_STR)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\",\"pinned\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.pinned").value(true));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("PATCH /v1/mcp/conversations/{id}: unknown or another subject's id returns 404")
    void update_unknownId_returns404() throws Exception {
        when(conversationService.update(eq(UNKNOWN_ID), any()))
                .thenThrow(new ConversationNotFoundException("Conversation not found: " + UNKNOWN_ID_STR));

        mockMvc.perform(patch(BASE + "/" + UNKNOWN_ID_STR)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinned\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("PATCH /v1/mcp/conversations/{id}: id that is not a valid UUID returns 400")
    void update_malformedId_returns400() throws Exception {
        mockMvc.perform(patch(BASE + "/does-not-exist")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinned\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("DELETE /v1/mcp/conversations/{id} returns 204")
    void delete_returns204() throws Exception {
        mockMvc.perform(delete(BASE + "/" + CONVERSATION_ID_STR).with(csrf())).andExpect(status().isNoContent());

        verify(conversationService).delete(CONVERSATION_ID);
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("DELETE /v1/mcp/conversations/{id}: unknown or another subject's id returns 404")
    void delete_unknownId_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new ConversationNotFoundException("Conversation not found: " + UNKNOWN_ID_STR))
                .when(conversationService)
                .delete(UNKNOWN_ID);

        mockMvc.perform(delete(BASE + "/" + UNKNOWN_ID_STR).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("DELETE /v1/mcp/conversations/{id}: id that is not a valid UUID returns 400")
    void delete_malformedId_returns400() throws Exception {
        mockMvc.perform(delete(BASE + "/does-not-exist").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("DELETE /v1/mcp/conversations (clear all) returns 204 even with zero conversations")
    void deleteAll_returns204() throws Exception {
        mockMvc.perform(delete(BASE).with(csrf())).andExpect(status().isNoContent());

        verify(conversationService).deleteAll();
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/conversations/{id}/messages returns 201 with the stored message")
    void appendMessage_returns201() throws Exception {
        when(conversationService.appendMessage(eq(CONVERSATION_ID), any(AppendMessageRequest.class)))
                .thenReturn(new ConversationMessage(
                        MESSAGE_ID, "user", UPDATED_AT, List.of(new ChatBlock.MarkdownBlock("hi")), "hi"));

        mockMvc.perform(post(BASE + "/" + CONVERSATION_ID_STR + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"blocks\":[{\"kind\":\"markdown\",\"markdown\":\"hi\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.content").value("hi"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/conversations/{id}/messages: unknown or another subject's id returns 404")
    void appendMessage_unknownId_returns404() throws Exception {
        when(conversationService.appendMessage(eq(UNKNOWN_ID), any(AppendMessageRequest.class)))
                .thenThrow(new ConversationNotFoundException("Conversation not found: " + UNKNOWN_ID_STR));

        mockMvc.perform(post(BASE + "/" + UNKNOWN_ID_STR + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"blocks\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST .../messages: id that is not a valid UUID returns 400")
    void appendMessage_malformedId_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/does-not-exist/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"blocks\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST .../messages: an unrecognized block kind fails to deserialize and returns 400")
    void appendMessage_unknownBlockKind_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/" + CONVERSATION_ID_STR + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"blocks\":[{\"kind\":\"bogus\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST .../messages: a null blocks entry returns 400 VALIDATION_ERROR, not 500")
    void appendMessage_nullBlockEntry_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/" + CONVERSATION_ID_STR + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"blocks\":[null],\"content\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(conversationService);
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST .../messages: more than 64 blocks returns 400 with a fieldErrors entry for blocks")
    void appendMessage_tooManyBlocks_returns400() throws Exception {
        StringBuilder blocks = new StringBuilder("[");
        for (int i = 0; i < 65; i++) {
            if (i > 0) {
                blocks.append(',');
            }
            blocks.append("{\"kind\":\"markdown\",\"markdown\":\"x\"}");
        }
        blocks.append(']');

        mockMvc.perform(post(BASE + "/" + CONVERSATION_ID_STR + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"blocks\":" + blocks + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("blocks"));
    }

    @Test
    @WithMockUser(authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST .../messages: an invalid role returns 400 with a fieldErrors entry for role")
    void appendMessage_invalidRole_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/" + CONVERSATION_ID_STR + "/messages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"bogus\",\"blocks\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("role"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_USER")
    @DisplayName("GET /v1/mcp/conversations: authenticated caller without mcp:chat:execute is denied 403")
    void list_withoutChatExecuteAuthority_returns403() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/mcp/conversations: unauthenticated caller returns 401")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {}
}
