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
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * #2497: on the chat path pos-security-service's audit export is offered only through {@link
 * AuditExportWriteGuard}, with the webhook destination read beside it. DOWNLOAD runs as called;
 * WEBHOOK re-reads the destination as the caller, must name the URL it previewed, and shares the turn's
 * one guarded write with the accounting event writes. Without the guard wired the export is not offered.
 *
 * <p>The proxy factory is an unstubbed mock: a call that reaches the delegate touches it, so "the
 * export ran" is "the proxy factory was used".
 */
class OpenApiToolProviderAuditExportGuardTest {

    private static final String AUTH = "Bearer caller-token";
    private static final String URL = "https://siem.example.com/hooks/audit";

    private static final DiscoveredOperation EXPORT = new DiscoveredOperation(
            AuditExportWriteGuard.EXPORT_TOOL,
            "Submits an asynchronous audit export job for the caller's tenant.",
            "POST",
            "/security-service/v1/audit/exports",
            "http://gateway.test",
            null,
            List.of("security:audit:export"));

    private static final DiscoveredOperation DESTINATION_READ = new DiscoveredOperation(
            AuditExportWriteGuard.DESTINATION_READ_TOOL,
            "Returns the HTTPS endpoint the caller's tenant receives WEBHOOK audit exports at.",
            "GET",
            "/security-service/v1/audit/exports/webhook",
            "http://gateway.test",
            null,
            List.of("security:audit_export:webhook_manage", "security:audit:export"));

    private static final DiscoveredOperation RETRY = new DiscoveredOperation(
            AccountingEventWriteGuard.RETRY_TOOL,
            "Re-runs pipeline processing for a failed accounting event.",
            "POST",
            "/accounting/v1/accounting/events/{eventId}/retry",
            "http://gateway.test",
            null,
            List.of("accounting:events:retry"));

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
                        "admin",
                        UUID.fromString("00000000-0000-7000-8000-000000000301"),
                        "ROLE_ADMIN",
                        Set.of("ROLE_ADMIN"),
                        Set.of("AUTHENTICATED"),
                        Set.of("security:audit:export", "accounting:events:retry", "accounting:events:view")),
                AUTH);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(DESTINATION_READ, EXPORT));
    }

    @AfterEach
    void cleanup() {
        userContext.clear();
    }

    private void wireGuard() {
        provider.setAuditExportGuard(new AuditExportWriteGuard(readExecutor, objectMapper));
    }

    private void destinationReads(String url) {
        when(readExecutor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                .thenReturn("{\"url\":\"" + url + "\",\"registeredAt\":\"2026-10-12T09:00:00Z\","
                        + "\"registeredBy\":\"admin.alpha\"}");
    }

    private static String download() {
        return "{\"body\":{\"format\":\"CSV\",\"deliveryMode\":\"DOWNLOAD\"}}";
    }

    private static String webhook(String namedDestination) {
        return "{\"body\":{\"format\":\"CSV\",\"deliveryMode\":\"WEBHOOK\"}"
                + (namedDestination == null
                        ? ""
                        : ",\"headers\":{\"" + AuditExportWriteGuard.DESTINATION_HEADER + "\":\"" + namedDestination
                                + "\"}")
                + "}";
    }

    private static ToolCallback named(List<ToolCallback> callbacks, String name) {
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private boolean exportRan() {
        return !mockingDetails(proxyFactory).getInvocations().isEmpty();
    }

    @Test
    @DisplayName("without the guard wired the export is not offered, DOWNLOAD included; the destination read still is")
    void notOfferedWithoutGuard() {
        List<ToolCallback> callbacks = provider.resolveToolCallbacks("export the audit log");

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(AuditExportWriteGuard.DESTINATION_READ_TOOL);
    }

    @Test
    @DisplayName("the export brings the destination read along, through the permission gate")
    void exportBringsTheDestinationRead() {
        wireGuard();
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(EXPORT));
        when(repository.findDiscoveredByNamesForPermissions(
                        eq(AuditExportWriteGuard.COMPANION_TOOLS), any(), anyString()))
                .thenReturn(List.of(DESTINATION_READ));

        List<ToolCallback> callbacks = provider.resolveToolCallbacks("send the audit export to our SIEM");

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(AuditExportWriteGuard.EXPORT_TOOL, AuditExportWriteGuard.DESTINATION_READ_TOOL);
    }

    @Test
    @DisplayName("the export's description carries the guard's rules; the destination read's does not")
    void descriptionCarriesGuardNote() {
        wireGuard();

        List<ToolCallback> callbacks = provider.resolveToolCallbacks("export the audit log");

        assertThat(named(callbacks, AuditExportWriteGuard.EXPORT_TOOL)
                        .getToolDefinition()
                        .description())
                .contains("Submits an asynchronous audit export", "WEBHOOK is HIGH risk", "leave the shop");
        assertThat(named(callbacks, AuditExportWriteGuard.DESTINATION_READ_TOOL)
                        .getToolDefinition()
                        .description())
                .doesNotContain("Write guard");
        assertThat(userContext.currentWriteCapableToolsPresent()).isTrue();
    }

    @Test
    @DisplayName("DOWNLOAD runs as called: no destination read, and the turn's guarded write is not spent")
    void downloadRunsUnguarded() {
        wireGuard();
        List<ToolCallback> turn = provider.resolveToolCallbacks("export the audit log");
        ToolCallback export = named(turn, AuditExportWriteGuard.EXPORT_TOOL);

        export.call(download());
        export.call(download());

        assertThat(exportRan()).isTrue();
        verifyNoInteractions(readExecutor);
    }

    @Test
    @DisplayName("WEBHOOK naming the destination it previewed reads it as the caller, then runs")
    void webhookNamingTheDestinationRuns() {
        wireGuard();
        destinationReads(URL);
        ToolCallback export =
                named(provider.resolveToolCallbacks("send it to the SIEM"), AuditExportWriteGuard.EXPORT_TOOL);

        export.call(webhook(URL));

        verify(readExecutor).execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH);
        assertThat(exportRan()).isTrue();
    }

    @Test
    @DisplayName("WEBHOOK without the previewed destination is refused, naming the URL to preview, and nothing runs")
    void webhookWithoutNamedDestinationRefused() {
        wireGuard();
        destinationReads(URL);
        ToolCallback export =
                named(provider.resolveToolCallbacks("send it to the SIEM"), AuditExportWriteGuard.EXPORT_TOOL);

        String result = export.call(webhook(null));

        assertThat(result).startsWith("Error: ").contains(URL, AuditExportWriteGuard.DESTINATION_HEADER);
        verifyNoInteractions(proxyFactory);
    }

    @Test
    @DisplayName("WEBHOOK whose destination changed since the preview is refused, and nothing runs")
    void webhookToAChangedDestinationRefused() {
        wireGuard();
        destinationReads("https://elsewhere.example.net/collect");
        ToolCallback export =
                named(provider.resolveToolCallbacks("send it to the SIEM"), AuditExportWriteGuard.EXPORT_TOOL);

        String result = export.call(webhook(URL));

        assertThat(result).startsWith("Error: ").contains("https://elsewhere.example.net/collect");
        verifyNoInteractions(proxyFactory);
    }

    @Test
    @DisplayName("WEBHOOK with no registered destination is refused without spending the turn")
    void webhookUnregisteredRefused() {
        wireGuard();
        when(readExecutor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                .thenThrow(new WritePlanExecutionException("Error: 404 Not Found from GET"));
        List<ToolCallback> turn = provider.resolveToolCallbacks("send it to the SIEM");
        ToolCallback export = named(turn, AuditExportWriteGuard.EXPORT_TOOL);

        String first = export.call(webhook(URL));
        String second = export.call(webhook(URL));

        assertThat(first).startsWith("Error: ").contains("No audit export webhook is registered");
        assertThat(second).contains("No audit export webhook is registered");
        verifyNoInteractions(proxyFactory);
    }

    @Test
    @DisplayName("a call carrying its own Authorization header is refused unread")
    void ownAuthorizationRefused() {
        wireGuard();
        ToolCallback export =
                named(provider.resolveToolCallbacks("send it to the SIEM"), AuditExportWriteGuard.EXPORT_TOOL);

        String result = export.call("{\"body\":{\"format\":\"CSV\",\"deliveryMode\":\"WEBHOOK\"},"
                + "\"headers\":{\"Authorization\":\"Bearer someone-else\",\""
                + AuditExportWriteGuard.DESTINATION_HEADER + "\":\"" + URL + "\"}}");

        assertThat(result).startsWith("Error: ").contains("own Authorization header");
        verifyNoInteractions(proxyFactory);
        verifyNoInteractions(readExecutor);
    }

    @Test
    @DisplayName("one turn runs one guarded write: a second WEBHOOK export is refused unread")
    void secondWebhookInTheTurnRefused() {
        wireGuard();
        destinationReads(URL);
        List<ToolCallback> turn = provider.resolveToolCallbacks("send it twice");
        named(turn, AuditExportWriteGuard.EXPORT_TOOL).call(webhook(URL));

        String again = named(turn, AuditExportWriteGuard.EXPORT_TOOL).call(webhook(URL));

        assertThat(again)
                .startsWith("Error: one confirmation covers one guarded write")
                .contains(AuditExportWriteGuard.EXPORT_TOOL + " WEBHOOK to " + URL);
        verify(readExecutor, times(1)).execute(eq(AuditExportWriteGuard.DESTINATION_READ_TOOL), anyString(), any());
    }

    @Test
    @DisplayName("the turn's one guarded write is shared with the accounting event writes")
    void sharedWithAccountingGuard() {
        wireGuard();
        provider.setWriteGuard(new AccountingEventWriteGuard(readExecutor, objectMapper));
        destinationReads(URL);
        when(repository.findDiscoveredCandidatesForPermissions(any(), anyInt(), any(), anyString()))
                .thenReturn(List.of(DESTINATION_READ, EXPORT, RETRY));
        List<ToolCallback> turn = provider.resolveToolCallbacks("export and retry");
        named(turn, AuditExportWriteGuard.EXPORT_TOOL).call(webhook(URL));

        String retry = named(turn, AccountingEventWriteGuard.RETRY_TOOL)
                .call("{\"pathParams\":{\"eventId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b\"}}");

        assertThat(retry).startsWith("Error: one confirmation covers one accounting event");
        verify(readExecutor, never()).execute(eq(AccountingEventWriteGuard.EVENT_READ_TOOL), anyString(), any());
    }

    @Test
    @DisplayName("invalid arguments are refused before anything is read or run")
    void invalidArguments() {
        wireGuard();
        ToolCallback export =
                named(provider.resolveToolCallbacks("export the audit log"), AuditExportWriteGuard.EXPORT_TOOL);

        assertThat(export.call("not json")).isEqualTo("Error: invalid tool arguments; nothing was run.");
        verifyNoInteractions(proxyFactory);
        verifyNoInteractions(readExecutor);
    }
}
