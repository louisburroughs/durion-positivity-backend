package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.ConversationStore;
import com.positivity.mcp.internal.service.ConversationTurnServiceImpl;
import com.positivity.mcp.internal.service.CurrentUserContextResolver;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller slice tests for {@link McpChatController}.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s
 * {@code WebCommonErrorAutoConfiguration}, so it is imported explicitly (#1694) to exercise the
 * real fallback chain an unmapped exception now falls through to, since {@link NltiExceptionHandler}
 * no longer carries a blanket {@code @ExceptionHandler(Exception.class)}.
 */
@WebMvcTest(McpChatController.class)
@Import({WebCommonErrorAutoConfiguration.class, ConversationTurnServiceImpl.class})
@ActiveProfiles("test")
class McpChatControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrationService agentOrchestrationService;

    @MockitoBean
    private CurrentUserContextResolver currentUserContextResolver;

    /**
     * #2073: the controller delegates to the real {@link ConversationTurnServiceImpl} (imported
     * above) so these cases keep exercising orchestration + segmentation; persistence is mocked
     * (an unstubbed {@code recordChatTurn} answers empty, i.e. {@code messageId} null).
     */
    @MockitoBean
    private ConversationStore conversationStore;

    @BeforeEach
    void stubUserContextResolver() {
        when(currentUserContextResolver.resolve(any(Authentication.class))).thenReturn(defaultUserContext());
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat with message returns 200 and response payload")
    void chat_withMessage_returns200() throws Exception {
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn("assistant reply");
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.response").value("assistant reply"));
    }

    @Test
    @WithMockUser(username = "admin.alpha", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat prefers ROLE_ADMIN when present in authorities")
    void chat_withAdminRole_usesAdminRoleForOrchestration() throws Exception {
        CurrentUserContext adminContext = new CurrentUserContext(
                "admin.alpha",
                UUID.fromString("00000000-0000-7000-8000-000000000123"),
                "ROLE_ADMIN",
                Set.of("ROLE_ADMIN"),
                Set.of("ROLE_ADMIN", McpPermissions.MCP_CHAT_EXECUTE),
                Set.of(McpPermissions.MCP_CHAT_EXECUTE, "AUTHENTICATED"));
        when(currentUserContextResolver.resolve(any(Authentication.class))).thenReturn(adminContext);
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn("assistant reply");
        var authentication = new UsernamePasswordAuthenticationToken(
                "admin.alpha",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("assistant reply"));

        verify(agentOrchestrationService)
                .chat(
                        argThat(context -> context.username().equals("admin.alpha")
                                && context.userId().equals(adminContext.userId())
                                && context.primaryRole().equals("ROLE_ADMIN")),
                        org.mockito.ArgumentMatchers.eq("test"),
                        nullable(String.class));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat orchestration failure returns 500 ApiError envelope")
    void chat_orchestrationFailure_returns500ApiError() throws Exception {
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenThrow(new RuntimeException("boom"));

        // #1694: NltiExceptionHandler no longer carries a blanket @ExceptionHandler(Exception.class),
        // so this now falls through to pos-web-common's GlobalApiExceptionHandler catch-all --
        // canonical code INTERNAL_ERROR (not INTERNAL_SERVER_ERROR) and a generic message that
        // never echoes the exception's own text ("boom" must not appear in the body).
        //
        // The principal is what the other tests supply and this one did not: without it
        // CurrentUserContextResolver.resolve(null) does not match the @BeforeEach stub, returns
        // null, and the controller NPEs before orchestration is ever called (#1711).
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user", "n/a", List.of(new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        // #1711: the 500 envelope is identical whether the failure came from orchestration or from
        // an NPE earlier in the controller, so without this the test passes while covering neither.
        verify(agentOrchestrationService).chat(any(CurrentUserContext.class), anyString(), nullable(String.class));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat forwards conversationId to the orchestrator (#1735)")
    void chat_forwardsConversationId() throws Exception {
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn("assistant reply");
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\",\"conversationId\":\"gate-q07\"}"))
                .andExpect(status().isOk());

        // Without this the field could be accepted, documented and silently dropped, which reads
        // as a working feature from outside and changes nothing about the shared memory.
        verify(agentOrchestrationService)
                .chat(
                        any(CurrentUserContext.class),
                        org.mockito.ArgumentMatchers.eq("test"),
                        org.mockito.ArgumentMatchers.eq("gate-q07"));
    }

    @Test
    @DisplayName("POST /v1/mcp/chat unauthenticated returns 401 ApiError envelope")
    void chat_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("POST /v1/mcp/chat: authenticated user without mcp:chat:execute authority is denied 403")
    @WithMockUser(authorities = "ROLE_USER")
    void postChat_withoutChatExecuteAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat with blank message returns 400 ApiError envelope")
    void chat_withBlankMessage_returns400() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("message"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: prose + table answer segments into markdown/table/markdown blocks (#2072)")
    void chat_proseAndTableAnswer_segmentsIntoBlocks() throws Exception {
        String agentMarkdown = "Here is the report:\n\n| Name | Status |\n| --- | --- |\n| Alpha | ACTIVE |\n\nThanks.";
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn(agentMarkdown);
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(agentMarkdown))
                .andExpect(jsonPath("$.blocks[0].kind").value("markdown"))
                .andExpect(jsonPath("$.blocks[0].markdown").value("Here is the report:"))
                .andExpect(jsonPath("$.blocks[1].kind").value("table"))
                .andExpect(jsonPath("$.blocks[1].title").doesNotExist())
                .andExpect(jsonPath("$.blocks[1].columns[0].label").value("Name"))
                .andExpect(jsonPath("$.blocks[1].columns[0].align").value("start"))
                .andExpect(jsonPath("$.blocks[1].columns[1].label").value("Status"))
                .andExpect(jsonPath("$.blocks[1].columns[1].align").value("start"))
                .andExpect(jsonPath("$.blocks[1].rows[0][0]").value("Alpha"))
                .andExpect(jsonPath("$.blocks[1].rows[0][1]").value("ACTIVE"))
                .andExpect(jsonPath("$.blocks[2].kind").value("markdown"))
                .andExpect(jsonPath("$.blocks[2].markdown").value("Thanks."));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: tool-free prose answer segments into a single markdown block (#2072)")
    void chat_toolFreeProseAnswer_singleMarkdownBlock() throws Exception {
        String agentMarkdown = "Just a simple prose answer with no tables or code.";
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn(agentMarkdown);
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(agentMarkdown))
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].kind").value("markdown"))
                .andExpect(jsonPath("$.blocks[0].markdown").value(agentMarkdown));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: fenced sql answer segments into markdown/code blocks (#2072)")
    void chat_fencedSqlAnswer_segmentsIntoCodeBlock() throws Exception {
        String agentMarkdown = "Here is the query:\n\n```sql\nSELECT 1;\n```";
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn(agentMarkdown);
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].kind").value("markdown"))
                .andExpect(jsonPath("$.blocks[1].kind").value("code"))
                .andExpect(jsonPath("$.blocks[1].language").value("sql"))
                .andExpect(jsonPath("$.blocks[1].code").value("SELECT 1;"));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: bare fence (no language) has no language field on the code block (#2072)")
    void chat_bareFenceAnswer_languageAbsent() throws Exception {
        String agentMarkdown = "```\nplain output\n```";
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn(agentMarkdown);
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].kind").value("code"))
                .andExpect(jsonPath("$.blocks[0].language").doesNotExist())
                .andExpect(jsonPath("$.blocks[0].code").value("plain output"));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: a table nested in a list item degrades to an empty blocks array (O1, #2072)")
    void chat_nestedTableAnswer_emptyBlocksArrayResponseUnchanged() throws Exception {
        // O1 (orchestrator, Wave 1 review cycle 2): blocks is either a faithful segmentation or
        // EMPTY — never a partial one carrying raw pipes the frontend cannot render. A prior edit
        // here asserted a single markdown-block "fallback", which contradicts O1 and current
        // production intent; restored to the spec's required contract. See ChatBlockSegmenterTest's
        // tableNestedInListItem_returnsEmptyList for the matching unit-level defect evidence: this
        // scenario currently segments to a single raw-pipe-carrying MarkdownBlock instead of [],
        // because the GFM table extension never forms a nested Table AST node for a table directly
        // following a list item's first line without a blank line, so the segmenter's
        // nested-node safety net has nothing to detect. This test is therefore expected to be RED
        // until that gap is closed in production — do not "fix" it by weakening this assertion.
        String agentMarkdown = "- item one\n  | A | B |\n  | --- | --- |\n  | 1 | 2 |";
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn(agentMarkdown);
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value(agentMarkdown))
                .andExpect(jsonPath("$.blocks").isArray())
                .andExpect(jsonPath("$.blocks.length()").value(0));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: response carries conversationId and messageId (#2073)")
    void chat_returnsConversationIdAndMessageId() throws Exception {
        UUID existingConversationId = UUID.fromString("0198f2b1-6c2a-7c3e-8f00-1234567890ab");
        UUID userId = defaultUserContext().userId();
        UUID assistantMessageId = UUID.randomUUID();
        when(conversationStore.isOwned(existingConversationId, userId)).thenReturn(true);
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString(), nullable(String.class)))
                .thenReturn("assistant reply");
        when(conversationStore.recordChatTurn(
                        eq(existingConversationId),
                        eq(false),
                        eq(userId),
                        anyString(),
                        anyList(),
                        anyString(),
                        anyList()))
                .thenReturn(Optional.of(assistantMessageId));
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\",\"conversationId\":\"" + existingConversationId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(existingConversationId.toString()))
                .andExpect(jsonPath("$.messageId").value(assistantMessageId.toString()));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: a conversationId UUID not found/owned returns 404 CONVERSATION_NOT_FOUND (#2073)")
    void chat_conversationIdNotFound_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        UUID userId = defaultUserContext().userId();
        when(conversationStore.isOwned(unknownId, userId)).thenReturn(false);
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                        .principal(authentication)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\",\"conversationId\":\"" + unknownId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        // The server must never silently start a new conversation under a caller-chosen id.
        verify(agentOrchestrationService, never())
                .chat(any(CurrentUserContext.class), anyString(), nullable(String.class));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat: a message over 32000 characters returns 400 ApiError envelope (#2073)")
    void chat_messageOverLengthLimit_returns400() throws Exception {
        String tooLong = "x".repeat(32001);

        mockMvc.perform(post("/v1/mcp/chat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("message"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verify(agentOrchestrationService, never())
                .chat(any(CurrentUserContext.class), anyString(), nullable(String.class));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }

        /** Required by pos-web-common's {@code GlobalApiExceptionHandler}. */
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }

    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // HTTP 403 set by @ResponseStatus
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
            // HTTP 401 set by @ResponseStatus
        }
    }

    private static CurrentUserContext defaultUserContext() {
        return new CurrentUserContext(
                "test-user",
                UUID.fromString("00000000-0000-7000-8000-000000000122"),
                "ROLE_USER",
                Set.of("ROLE_USER"),
                Set.of("ROLE_USER", McpPermissions.MCP_CHAT_EXECUTE),
                Set.of(McpPermissions.MCP_CHAT_EXECUTE, "AUTHENTICATED"));
    }
}
