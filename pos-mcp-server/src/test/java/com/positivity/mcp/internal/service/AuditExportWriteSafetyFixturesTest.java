package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2497: the write-safety fixtures tagged {@code audit-export-guard} hold for {@link
 * AuditExportWriteGuard} as built. Each fixture's {@code plan_args} is run through the guard: the risk
 * the fixture expects is the risk the guard's floor gives a LOW classification, DOWNLOAD is not guarded,
 * and the fixtures that assert it see the destination named in the preview, or the refusal when none
 * is registered. The structural rules for the same files live in {@code EvalFixtureValidationTest}.
 */
@DisplayName("write-safety fixtures for the audit export guard (#2497)")
class AuditExportWriteSafetyFixturesTest {

    private static final Path SUITE = Path.of(System.getProperty("user.dir"), "src/test/resources/eval/write-safety");
    private static final String TAG = "audit-export-guard";
    private static final String AUTH = "Bearer fixture-caller";
    private static final String DESTINATION = "https://siem.example.com/hooks/audit";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<JsonNode> fixtures() throws IOException {
        List<JsonNode> tagged = new ArrayList<>();
        try (Stream<Path> files = Files.list(SUITE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                for (JsonNode fixture : objectMapper.readTree(file.toFile()).path("fixtures")) {
                    for (JsonNode tag : fixture.path("tags")) {
                        if (TAG.equals(tag.asText())) {
                            tagged.add(fixture);
                        }
                    }
                }
            }
        }
        return tagged;
    }

    private Map<String, Object> planArgs(JsonNode fixture) {
        return objectMapper.convertValue(fixture.path("plan_args"), new TypeReference<Map<String, Object>>() {});
    }

    private static String id(JsonNode fixture) {
        return fixture.path("fixture_id").asText();
    }

    @Test
    @DisplayName("both delivery modes are covered, each fixture pins its arguments and targets the export tool")
    void bothModesCovered() throws IOException {
        List<JsonNode> fixtures = fixtures();

        assertThat(fixtures)
                .extracting(fixture -> fixture.path("tags").toString())
                .anyMatch(tags -> tags.contains("delivery-download"))
                .anyMatch(tags -> tags.contains("delivery-webhook"));
        for (JsonNode fixture : fixtures) {
            assertThat(fixture.path("expected").path("tool_id").asText())
                    .as("%s: tool_id", id(fixture))
                    .isEqualTo(AuditExportWriteGuard.EXPORT_TOOL);
            assertThat(fixture.path("plan_args").isObject())
                    .as("%s: plan_args", id(fixture))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the expected risk is the guard's floor over a LOW classification: DOWNLOAD LOW, WEBHOOK HIGH")
    void riskMatchesTheGuard() throws IOException {
        for (JsonNode fixture : fixtures()) {
            Map<String, Object> args = planArgs(fixture);
            NltiRiskLevel expected = NltiRiskLevel.valueOf(
                    fixture.path("expected").path("risk_level").asText());
            boolean download = fixture.path("tags").toString().contains("delivery-download");

            assertThat(AuditExportWriteGuard.riskFloor(AuditExportWriteGuard.EXPORT_TOOL, args, NltiRiskLevel.LOW))
                    .as("%s: risk", id(fixture))
                    .isEqualTo(expected);
            assertThat(AuditExportWriteGuard.guards(AuditExportWriteGuard.EXPORT_TOOL, args))
                    .as("%s: guarded", id(fixture))
                    .isEqualTo(!download);
        }
    }

    @Test
    @DisplayName("preview_names_destination: the guard's preview says where the audit data goes")
    void previewNamesTheDestination() throws IOException {
        WritePlanExecutor executor = mock(WritePlanExecutor.class);
        when(executor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                .thenReturn("{\"url\":\"" + DESTINATION + "\",\"registeredBy\":\"admin.alpha\"}");
        AuditExportWriteGuard guard = new AuditExportWriteGuard(executor, objectMapper);
        int checked = 0;

        for (JsonNode fixture : fixtures()) {
            if (!fixture.path("assertions").path("preview_names_destination").asBoolean(false)) {
                continue;
            }
            AuditExportWriteGuard.Inspection inspection =
                    guard.inspect(AuditExportWriteGuard.EXPORT_TOOL, planArgs(fixture), AUTH, false);

            assertThat(inspection.permitted()).as("%s: permitted", id(fixture)).isTrue();
            assertThat(inspection.message())
                    .as("%s: preview", id(fixture))
                    .contains("Audit data will leave the shop to " + DESTINATION);
            assertThat(inspection.fingerprint()).as("%s: pinned", id(fixture)).isNotNull();
            checked++;
        }
        assertThat(checked).as("fixtures asserting the destination preview").isPositive();
    }

    @Test
    @DisplayName("refused_without_destination: no registered webhook refuses before any preview")
    void refusedWithoutDestination() throws IOException {
        WritePlanExecutor executor = mock(WritePlanExecutor.class);
        when(executor.execute(AuditExportWriteGuard.DESTINATION_READ_TOOL, "{}", AUTH))
                .thenThrow(new WritePlanExecutionException("Error: 404 Not Found from GET"));
        AuditExportWriteGuard guard = new AuditExportWriteGuard(executor, objectMapper);
        int checked = 0;

        for (JsonNode fixture : fixtures()) {
            if (!fixture.path("assertions").path("refused_without_destination").asBoolean(false)) {
                continue;
            }
            AuditExportWriteGuard.Inspection inspection =
                    guard.inspect(AuditExportWriteGuard.EXPORT_TOOL, planArgs(fixture), AUTH, false);

            assertThat(inspection.permitted()).as("%s: refused", id(fixture)).isFalse();
            assertThat(inspection.message()).contains("No audit export webhook is registered");
            checked++;
        }
        assertThat(checked).as("fixtures asserting the unregistered refusal").isPositive();
    }
}
