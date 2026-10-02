package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.discovery.OperationProxyFactory;
import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * #2374: on the chat path pos-accounting's event retry and reprocess are offered only through
 * {@link AccountingEventWriteGuard}. The status is re-read as the caller before each call, the first
 * call that names an event spends the turn, and without the guard wired the tools are not offered.
 *
 * <p>The proxy factory is an unstubbed mock: a call that reaches the delegate touches it (and renders
 * a controlled error), so "the write ran" is "the proxy factory was used".
 */
class OpenApiToolProviderGuardedWriteTest {

    private static final String AUTH = "Bearer caller-token";
    private static final String EVENT_A = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b";
    private static final String EVENT_B = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c";

    private static final DiscoveredOperation RETRY = new DiscoveredOperation(
            AccountingEventWriteGuard.RETRY_TOOL,
            "Re-runs pipeline processing for a failed accounting event.",
            "POST",
            "/accounting/v1/accounting/events/{eventId}/retry",
            "http://gateway.test",
            null,
            List.of("accounting:events:retry"));

    private static final DiscoveredOperation REPROCESS = new DiscoveredOperation(
            AccountingEventWriteGuard.REPROCESS_TOOL,
            "Reprocesses a SUSPENDED accounting event after a mapping or rule correction.",
            "POST",
            "/accounting/v1/accounting/events/{eventId}/reprocess",
            "http://gateway.test",
            null,
            List.of("accounting:events:reprocess"));

    private static final DiscoveredOperation EVENT_READ = new DiscoveredOperation(
            AccountingEventWriteGuard.EVENT_READ_TOOL,
            "Fetches one accounting event.",
            "GET",
            "/accounting/v1/accounting/events/{eventId}",
            "http://gateway.test",
            null,
            List.of("accounting:events:view"));

    private final ToolMetadataRepository repository = mock(ToolMetadataRepository.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final OperationProxyFactory proxyFactory = mock(OperationProxyFactory.class);
    private final WritePlanExecutor readExecutor = mock(WritePlanExecutor.class);
    private final RequestScopedUserContext userContext = new RequestScopedUserContext();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenApiToolProvider provider = new OpenApiToolProvider(
            repository, embeddingModel, userContext, proxyFactory, objectMapper, 8, Duration.ofSeconds(30), null);

    @BeforeEach
    void caller() {
        userContext.set(
                new CurrentUserContext(
                        "controller",
                        UUID.fromString("00000000-0000-7000-8000-000000000201"),
                        "ROLE_CONTROLLER",
                        Set.of("ROLE_CONTROLLER"),
                        Set.of("AUTHENTICATED"),
                        Set.of("accounting:events:retry", "accounting:events:reprocess", "accounting:events:view")),
                AUTH);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(EVENT_READ, RETRY, REPROCESS));
    }

    @AfterEach
    void cleanup() {
        userContext.clear();
    }

    private void wireGuard() {
        provider.setWriteGuard(new AccountingEventWriteGuard(readExecutor, objectMapper));
    }

    private void eventReads(String eventId, String status) {
        when(readExecutor.execute(
                        AccountingEventWriteGuard.EVENT_READ_TOOL,
                        "{\"pathParams\":{\"eventId\":\"" + eventId + "\"}}",
                        AUTH))
                .thenReturn("{\"eventId\":\"" + eventId + "\",\"eventType\":\"INVOICE_FINALIZED\","
                        + "\"sourceSystem\":\"POS\",\"status\":\"" + status
                        + "\",\"payload\":{\"totalAmount\":150.00}}");
    }

    private static String args(String eventId) {
        return "{\"pathParams\":{\"eventId\":\"" + eventId + "\"}}";
    }

    private static ToolCallback named(List<ToolCallback> callbacks, String name) {
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private boolean writeRan() {
        return !mockingDetails(proxyFactory).getInvocations().isEmpty();
    }

    @Test
    @DisplayName("without the guard wired, retry and reprocess are not offered; the event read still is")
    void notOfferedWithoutGuard() {
        List<ToolCallback> callbacks = provider.resolveToolCallbacks("retry the failed invoice event");

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(AccountingEventWriteGuard.EVENT_READ_TOOL);
        assertThat(userContext.currentWriteCapableToolsPresent()).isFalse();
    }

    @Test
    @DisplayName("a guarded write outside the ranked cut's reads brings them along, through the permission gate")
    void guardedWriteBringsItsReads() {
        wireGuard();
        DiscoveredOperation history = new DiscoveredOperation(
                AccountingEventWriteGuard.HISTORY_READ_TOOL,
                "Lists an event's reprocessing attempts.",
                "GET",
                "/accounting/v1/accounting/events/{eventId}/reprocessing-history",
                "http://gateway.test",
                null,
                List.of("accounting:events:view"));
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(RETRY));
        when(repository.findDiscoveredByNamesForPermissions(
                        eq(AccountingEventWriteGuard.READ_TOOLS), any(), anyString()))
                .thenReturn(List.of(EVENT_READ, history));

        List<ToolCallback> callbacks = provider.resolveToolCallbacks("retry the failed invoice event");

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(
                        AccountingEventWriteGuard.RETRY_TOOL,
                        AccountingEventWriteGuard.EVENT_READ_TOOL,
                        AccountingEventWriteGuard.HISTORY_READ_TOOL);
    }

    @Test
    @DisplayName("no guarded write in the cut, or no guard wired: no read is added")
    void noGuardedWriteNoReadsAdded() {
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(RETRY));
        provider.resolveToolCallbacks("retry the failed invoice event");

        wireGuard();
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(EVENT_READ));
        provider.resolveToolCallbacks("show the event");

        verify(repository, never()).findDiscoveredByNamesForPermissions(any(), any(), anyString());
    }

    @Test
    @DisplayName("a guarded tool's description carries the guard's rules; an unguarded one does not")
    void descriptionCarriesGuardNote() {
        wireGuard();

        List<ToolCallback> callbacks = provider.resolveToolCallbacks("retry the failed invoice event");

        assertThat(named(callbacks, AccountingEventWriteGuard.RETRY_TOOL)
                        .getToolDefinition()
                        .description())
                .contains("Re-runs pipeline processing", "Write guard: HIGH risk", "no undo", "One confirmation");
        assertThat(named(callbacks, AccountingEventWriteGuard.REPROCESS_TOOL)
                        .getToolDefinition()
                        .description())
                .contains("SUSPENDED", "mapping version");
        assertThat(named(callbacks, AccountingEventWriteGuard.EVENT_READ_TOOL)
                        .getToolDefinition()
                        .description())
                .doesNotContain("Write guard");
        assertThat(userContext.currentWriteCapableToolsPresent()).isTrue();
    }

    @Test
    @DisplayName("a retry of a FAILED event reads the status as the caller, then runs")
    void failedEventRuns() {
        wireGuard();
        eventReads(EVENT_A, "FAILED");
        ToolCallback retry = named(provider.resolveToolCallbacks("retry it"), AccountingEventWriteGuard.RETRY_TOOL);

        retry.call(args(EVENT_A));

        verify(readExecutor).execute(eq(AccountingEventWriteGuard.EVENT_READ_TOOL), eq(args(EVENT_A)), eq(AUTH));
        assertThat(writeRan()).isTrue();
    }

    @Test
    @DisplayName("a retry of a PROCESSED event is refused with the guard's reason, and nothing is written")
    void processedEventRefused() {
        wireGuard();
        eventReads(EVENT_A, "PROCESSED");
        ToolCallback retry = named(provider.resolveToolCallbacks("retry it"), AccountingEventWriteGuard.RETRY_TOOL);

        String result = retry.call(args(EVENT_A));

        assertThat(result).startsWith("Error: ").contains(EVENT_A, "already posted", "reversing entry");
        verifyNoInteractions(proxyFactory);
    }

    @Test
    @DisplayName("one turn runs one guarded call: a second, on another event or tool, is refused unread")
    void secondGuardedCallInTheTurnRefused() {
        wireGuard();
        eventReads(EVENT_A, "FAILED");
        List<ToolCallback> turn = provider.resolveToolCallbacks("retry them all");
        named(turn, AccountingEventWriteGuard.RETRY_TOOL).call(args(EVENT_A));

        String again = named(turn, AccountingEventWriteGuard.RETRY_TOOL).call(args(EVENT_B));
        String other = named(turn, AccountingEventWriteGuard.REPROCESS_TOOL).call(args(EVENT_B));

        assertThat(again)
                .startsWith("Error: one confirmation covers one accounting event")
                .contains(AccountingEventWriteGuard.RETRY_TOOL + " for event " + EVENT_A);
        assertThat(other).startsWith("Error: one confirmation covers one accounting event");
        verify(readExecutor, times(1)).execute(anyString(), anyString(), any());
        verify(readExecutor, never()).execute(any(), eq(args(EVENT_B)), any());
    }

    @Test
    @DisplayName("a refused call that named an event still spends the turn: the next event is not reached")
    void refusedCallSpendsTheTurn() {
        wireGuard();
        eventReads(EVENT_A, "PROCESSED");
        List<ToolCallback> turn = provider.resolveToolCallbacks("retry it");
        named(turn, AccountingEventWriteGuard.RETRY_TOOL).call(args(EVENT_A));

        String next = named(turn, AccountingEventWriteGuard.RETRY_TOOL).call(args(EVENT_B));

        assertThat(next).startsWith("Error: one confirmation covers one accounting event");
        verifyNoInteractions(proxyFactory);
    }

    @Test
    @DisplayName("malformed arguments name no event: refused without spending the turn, so they can be corrected")
    void malformedArgumentsDoNotSpendTheTurn() {
        wireGuard();
        eventReads(EVENT_A, "FAILED");
        List<ToolCallback> turn = provider.resolveToolCallbacks("retry it");
        ToolCallback retry = named(turn, AccountingEventWriteGuard.RETRY_TOOL);

        assertThat(retry.call("{\"eventId\":\"" + EVENT_A + "\"}")).contains("exactly one accounting event id");
        assertThat(retry.call("not json")).isEqualTo("Error: invalid tool arguments; nothing was run.");
        assertThat(writeRan()).isFalse();

        retry.call(args(EVENT_A));

        assertThat(writeRan()).isTrue();
    }

    @Test
    @DisplayName("each turn gets its own allowance: a new resolution starts unspent")
    void allowanceIsPerTurn() {
        wireGuard();
        eventReads(EVENT_A, "FAILED");
        eventReads(EVENT_B, "FAILED");
        named(provider.resolveToolCallbacks("retry A"), AccountingEventWriteGuard.RETRY_TOOL)
                .call(args(EVENT_A));

        String nextTurn = named(provider.resolveToolCallbacks("retry B"), AccountingEventWriteGuard.RETRY_TOOL)
                .call(args(EVENT_B));

        assertThat(nextTurn).doesNotContain("one confirmation covers one accounting event");
        verify(readExecutor).execute(eq(AccountingEventWriteGuard.EVENT_READ_TOOL), eq(args(EVENT_B)), eq(AUTH));
    }

    @Test
    @DisplayName("the guard's arguments are the call's own: nothing is added to what the delegate executes")
    void argumentsPassThroughUnchanged() {
        wireGuard();
        eventReads(EVENT_A, "SUSPENDED");
        ToolCallback reprocess =
                named(provider.resolveToolCallbacks("reprocess it"), AccountingEventWriteGuard.REPROCESS_TOOL);
        String input = "{\"pathParams\":{\"eventId\":\"" + EVENT_A + "\"},\"body\":"
                + objectMapperWrite(Map.of("triggeredByUserId", "controller")) + "}";

        reprocess.call(input);

        assertThat(writeRan()).isTrue();
        verify(readExecutor).execute(eq(AccountingEventWriteGuard.EVENT_READ_TOOL), eq(args(EVENT_A)), eq(AUTH));
    }

    private String objectMapperWrite(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
