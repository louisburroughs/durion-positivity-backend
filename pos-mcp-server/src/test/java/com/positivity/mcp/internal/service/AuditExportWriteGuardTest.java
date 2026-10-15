package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #2497: the guard on pos-security-service's audit export — DOWNLOAD unguarded, WEBHOOK HIGH with the
 * destination read as the caller, named in the preview, pinned, and refused when there is none, when
 * it changed, or when the call carries its own credential.
 */
class AuditExportWriteGuardTest {

    private static final String AUTH = "Bearer caller-token";
    private static final String TOOL = AuditExportWriteGuard.EXPORT_TOOL;
    private static final String URL = "https://siem.example.com/hooks/audit";

    private final WritePlanExecutor executor = mock(WritePlanExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditExportWriteGuard guard = new AuditExportWriteGuard(executor, objectMapper);

    private void destinationReads(String url) {
        when(executor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                .thenReturn("{\"url\":\"" + url + "\",\"registeredAt\":\"2026-10-12T09:00:00Z\","
                        + "\"registeredBy\":\"admin.alpha\"}");
    }

    private static Map<String, Object> export(String deliveryMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("format", "CSV");
        if (deliveryMode != null) {
            body.put("deliveryMode", deliveryMode);
        }
        body.put("filters", Map.of("eventType", "PERMISSION_DENIED", "fromDate", "2026-10-01T00:00:00Z"));
        return Map.of("body", body);
    }

    private static Map<String, Object> withHeaders(Map<String, Object> args, Map<String, Object> headers) {
        Map<String, Object> copy = new HashMap<>(args);
        copy.put("headers", headers);
        return copy;
    }

    @Nested
    @DisplayName("which calls are guarded")
    class Scope {

        @Test
        @DisplayName("the export tool is covered whatever its arguments; other tools are not")
        void coversOnlyTheExport() {
            assertThat(AuditExportWriteGuard.covers("security-service_requestauditexport"))
                    .isTrue();
            assertThat(AuditExportWriteGuard.covers("security-service_getauditexportjob"))
                    .isFalse();
            assertThat(AuditExportWriteGuard.covers("security-service_getauditexportwebhook"))
                    .isFalse();
            assertThat(AuditExportWriteGuard.covers(null)).isFalse();
        }

        @Test
        @DisplayName("DOWNLOAD is not guarded and keeps its risk; WEBHOOK is guarded and HIGH")
        void downloadLowWebhookHigh() {
            assertThat(AuditExportWriteGuard.guards(TOOL, export("DOWNLOAD"))).isFalse();
            assertThat(AuditExportWriteGuard.riskFloor(TOOL, export("DOWNLOAD"), NltiRiskLevel.LOW))
                    .isEqualTo(NltiRiskLevel.LOW);
            assertThat(AuditExportWriteGuard.guards(TOOL, export("WEBHOOK"))).isTrue();
            assertThat(AuditExportWriteGuard.riskFloor(TOOL, export("WEBHOOK"), NltiRiskLevel.LOW))
                    .isEqualTo(NltiRiskLevel.HIGH);
            assertThat(AuditExportWriteGuard.riskFloor("orders_createorder", export("WEBHOOK"), NltiRiskLevel.LOW))
                    .isEqualTo(NltiRiskLevel.LOW);
        }

        @Test
        @DisplayName(
                "anything not plainly DOWNLOAD is guarded: a missing or unknown mode, no body, a lower-case webhook")
        void failClosed() {
            assertThat(AuditExportWriteGuard.guards(TOOL, export(null))).isTrue();
            assertThat(AuditExportWriteGuard.guards(TOOL, export("SFTP"))).isTrue();
            assertThat(AuditExportWriteGuard.guards(TOOL, export("webhook"))).isTrue();
            assertThat(AuditExportWriteGuard.guards(TOOL, Map.of())).isTrue();
            assertThat(AuditExportWriteGuard.guards(TOOL, null)).isTrue();
            assertThat(AuditExportWriteGuard.guards(TOOL, export(" download "))).isFalse();
        }

        @Test
        @DisplayName("a body sent as a JSON string is read too, and a duplicated deliveryMode key is not trusted")
        void stringBody() {
            assertThat(AuditExportWriteGuard.guards(
                            TOOL, Map.of("body", "{\"format\":\"CSV\",\"deliveryMode\":\"DOWNLOAD\"}")))
                    .isFalse();
            assertThat(AuditExportWriteGuard.guards(
                            TOOL, Map.of("body", "{\"format\":\"CSV\",\"deliveryMode\":\"WEBHOOK\"}")))
                    .isTrue();
            assertThat(AuditExportWriteGuard.guards(
                            TOOL, Map.of("body", "{\"deliveryMode\":\"DOWNLOAD\",\"deliveryMode\":\"WEBHOOK\"}")))
                    .isTrue();
            assertThat(AuditExportWriteGuard.guards(TOOL, Map.of("body", "not json")))
                    .isTrue();
        }

        @Test
        @DisplayName("inspect refuses to judge a DOWNLOAD call or another tool")
        void inspectRejectsUnguarded() {
            assertThatThrownBy(() -> guard.inspect(TOOL, export("DOWNLOAD"), AUTH, false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> guard.inspect("orders_createorder", export("WEBHOOK"), AUTH, false))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(executor);
        }
    }

    @Nested
    @DisplayName("WEBHOOK inspection")
    class Webhook {

        @Test
        @DisplayName("reads the destination as the caller and previews where the audit data will go")
        void previewNamesTheDestination() {
            destinationReads(URL);

            AuditExportWriteGuard.Inspection inspection = guard.inspect(TOOL, export("WEBHOOK"), AUTH, false);

            assertThat(inspection.permitted()).isTrue();
            assertThat(inspection.destination()).isEqualTo(URL);
            assertThat(inspection.fingerprint()).hasSize(64);
            assertThat(inspection.message())
                    .contains(
                            "Audit export by WEBHOOK",
                            "CSV",
                            "of type PERMISSION_DENIED",
                            "from 2026-10-01T00:00:00Z",
                            "Audit data will leave the shop to " + URL,
                            "registered by admin.alpha at 2026-10-12T09:00:00Z",
                            "cannot be recalled",
                            "one export only");
            verify(executor).execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH);
        }

        @Test
        @DisplayName("no filters reads as every audit event")
        void noFilters() {
            destinationReads(URL);

            AuditExportWriteGuard.Inspection inspection = guard.inspect(
                    TOOL, Map.of("body", Map.of("format", "json", "deliveryMode", "WEBHOOK")), AUTH, false);

            assertThat(inspection.message()).contains("a JSON file of every audit event (no filters)");
        }

        @Test
        @DisplayName("no registered destination (the read answers 404) refuses and points at DOWNLOAD")
        void unregisteredRefused() {
            when(executor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                    .thenThrow(new WritePlanExecutionException(
                            "Write-plan tool execution failed for tool: Error: 404 Not Found from GET"));

            AuditExportWriteGuard.Inspection inspection = guard.inspect(TOOL, export("WEBHOOK"), AUTH, false);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.destination()).isNull();
            assertThat(inspection.fingerprint()).isNull();
            assertThat(inspection.message())
                    .contains("No audit export webhook is registered", "nothing was run", "DOWNLOAD");
        }

        @Test
        @DisplayName("any other failed or unreadable read refuses: fail-closed")
        void unreadableRefused() {
            when(executor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                    .thenThrow(new WritePlanExecutionException("Error: 503 Service Unavailable"))
                    .thenReturn("not json")
                    .thenReturn("{\"registeredBy\":\"admin\"}");

            assertThat(guard.inspect(TOOL, export("WEBHOOK"), AUTH, false).message())
                    .startsWith("Could not read the shop's audit export webhook destination");
            assertThat(guard.inspect(TOOL, export("WEBHOOK"), AUTH, false).permitted())
                    .isFalse();
            assertThat(guard.inspect(TOOL, export("WEBHOOK"), AUTH, false).message())
                    .contains("without a URL");
        }

        @Test
        @DisplayName("an unknown delivery mode is refused without a read")
        void unknownModeRefused() {
            AuditExportWriteGuard.Inspection inspection = guard.inspect(TOOL, export("SFTP"), AUTH, false);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.message()).contains("DOWNLOAD or WEBHOOK");
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("arguments carrying their own Authorization header are refused without a read")
        void ownAuthorizationRefused() {
            AuditExportWriteGuard.Inspection inspection = guard.inspect(
                    TOOL, withHeaders(export("WEBHOOK"), Map.of("authorization", "Bearer someone-else")), AUTH, false);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.message()).contains("own Authorization header");
            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("the fingerprint pins the destination: a re-registration elsewhere changes it")
        void fingerprintTracksTheDestination() {
            destinationReads(URL);
            String before = guard.inspect(TOOL, export("WEBHOOK"), AUTH, false).fingerprint();
            String again = guard.inspect(TOOL, export("WEBHOOK"), AUTH, false).fingerprint();

            destinationReads("https://elsewhere.example.net/collect");
            String after = guard.inspect(TOOL, export("WEBHOOK"), AUTH, false).fingerprint();

            assertThat(again).isEqualTo(before);
            assertThat(after).isNotEqualTo(before);
        }
    }

    @Nested
    @DisplayName("chat path: the call names the destination it previewed")
    class NamedDestination {

        @Test
        @DisplayName("required on the chat path: a call that names none is refused, naming the destination to preview")
        void requiredOnChat() {
            destinationReads(URL);

            AuditExportWriteGuard.Inspection inspection = guard.inspect(TOOL, export("WEBHOOK"), AUTH, true);

            assertThat(inspection.permitted()).isFalse();
            assertThat(inspection.destination()).isEqualTo(URL);
            assertThat(inspection.message()).contains(URL, AuditExportWriteGuard.DESTINATION_HEADER, "confirm");
        }

        @ParameterizedTest
        @ValueSource(strings = {AuditExportWriteGuard.DESTINATION_HEADER, "x-audit-export-destination"})
        @DisplayName("the previewed URL, in any header-name case, is permitted")
        void matchingPermitted(String headerName) {
            destinationReads(URL);

            AuditExportWriteGuard.Inspection inspection =
                    guard.inspect(TOOL, withHeaders(export("WEBHOOK"), Map.of(headerName, URL)), AUTH, true);

            assertThat(inspection.permitted()).isTrue();
        }

        @Test
        @DisplayName("a destination that changed since the preview is refused, on either path")
        void changedRefused() {
            destinationReads("https://elsewhere.example.net/collect");
            Map<String, Object> args =
                    withHeaders(export("WEBHOOK"), Map.of(AuditExportWriteGuard.DESTINATION_HEADER, URL));

            AuditExportWriteGuard.Inspection chat = guard.inspect(TOOL, args, AUTH, true);
            AuditExportWriteGuard.Inspection plan = guard.inspect(TOOL, args, AUTH, false);

            assertThat(chat.permitted()).isFalse();
            assertThat(chat.message()).contains("https://elsewhere.example.net/collect", "not " + URL);
            assertThat(plan.permitted()).isFalse();
        }
    }

    @Test
    @DisplayName("the guard note carries the rules the model reads; other tools get none")
    void guardNote() {
        assertThat(AuditExportWriteGuard.guardNote(TOOL))
                .contains(
                        "DOWNLOAD is an ordinary export",
                        "WEBHOOK is HIGH risk",
                        AuditExportWriteGuard.DESTINATION_READ_TOOL,
                        "audit data will leave the shop to <url>",
                        AuditExportWriteGuard.DESTINATION_HEADER,
                        "never your own Authorization header");
        assertThat(AuditExportWriteGuard.guardNote("orders_createorder")).isEmpty();
    }

    @Test
    @DisplayName("the destination read relays exactly the caller's credential and nothing else")
    void readsAsTheCaller() {
        destinationReads(URL);

        guard.inspect(TOOL, export("WEBHOOK"), AUTH, false);

        verify(executor).execute(any(), anyString(), org.mockito.ArgumentMatchers.eq(AUTH));
    }
}
