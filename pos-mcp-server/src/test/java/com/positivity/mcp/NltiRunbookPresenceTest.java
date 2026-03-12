package com.positivity.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that required operational documentation files exist and are non-empty.
 *
 * <p>Tests AC6–AC8 of NLTI-009: all runbooks, the NLTI dashboard descriptor, and the
 * alert rules descriptor must be present at their canonical locations so that the on-call
 * runbook is reachable from any incident management link.
 *
 * <p>These tests pass immediately; they provide regression protection against accidental
 * deletion of operational documentation.
 *
 * Issue: NLTI-009
 */
class NltiRunbookPresenceTest {

    // Maven Surefire sets user.dir to the module directory at test runtime.
    private static final Path DOCS_ROOT = Paths.get(System.getProperty("user.dir"), "docs");

    // ─── AC6-AC8: Runbooks ────────────────────────────────────────────────

    @Test
    @DisplayName("authz-outage runbook exists and is non-empty")
    void authzOutageRunbookExists() {
        Path runbook = DOCS_ROOT.resolve("runbooks/authz-outage.md");
        assertThat(Files.exists(runbook))
                .as("runbooks/authz-outage.md must exist").isTrue();
        assertThat(sizeOf(runbook))
                .as("runbooks/authz-outage.md must be non-empty").isGreaterThan(0L);
    }

    @Test
    @DisplayName("downstream-timeout runbook exists and is non-empty")
    void downstreamTimeoutRunbookExists() {
        Path runbook = DOCS_ROOT.resolve("runbooks/downstream-timeout.md");
        assertThat(Files.exists(runbook))
                .as("runbooks/downstream-timeout.md must exist").isTrue();
        assertThat(sizeOf(runbook))
                .as("runbooks/downstream-timeout.md must be non-empty").isGreaterThan(0L);
    }

    @Test
    @DisplayName("audit-storage-failure runbook exists and is non-empty")
    void auditStorageFailureRunbookExists() {
        Path runbook = DOCS_ROOT.resolve("runbooks/audit-storage-failure.md");
        assertThat(Files.exists(runbook))
                .as("runbooks/audit-storage-failure.md must exist").isTrue();
        assertThat(sizeOf(runbook))
                .as("runbooks/audit-storage-failure.md must be non-empty").isGreaterThan(0L);
    }

    @Test
    @DisplayName("planning-failure runbook exists and is non-empty")
    void planningFailureRunbookExists() {
        Path runbook = DOCS_ROOT.resolve("runbooks/planning-failure.md");
        assertThat(Files.exists(runbook))
                .as("runbooks/planning-failure.md must exist").isTrue();
        assertThat(sizeOf(runbook))
                .as("runbooks/planning-failure.md must be non-empty").isGreaterThan(0L);
    }

    @Test
    @DisplayName("confirmation-gate-mismatch runbook exists and is non-empty")
    void confirmationGateMismatchRunbookExists() {
        Path runbook = DOCS_ROOT.resolve("runbooks/confirmation-gate-mismatch.md");
        assertThat(Files.exists(runbook))
                .as("runbooks/confirmation-gate-mismatch.md must exist").isTrue();
        assertThat(sizeOf(runbook))
                .as("runbooks/confirmation-gate-mismatch.md must be non-empty").isGreaterThan(0L);
    }

    // ─── AC6-AC8: Dashboard & Alerts ──────────────────────────────────────

    @Test
    @DisplayName("nlti-overview dashboard descriptor exists and is non-empty")
    void dashboardDescriptorExists() {
        Path dashboard = DOCS_ROOT.resolve("dashboards/nlti-overview.md");
        assertThat(Files.exists(dashboard))
                .as("dashboards/nlti-overview.md must exist").isTrue();
        assertThat(sizeOf(dashboard))
                .as("dashboards/nlti-overview.md must be non-empty").isGreaterThan(0L);
    }

    @Test
    @DisplayName("nlti-alerts alert rules descriptor exists and is non-empty")
    void alertRulesDescriptorExists() {
        Path alerts = DOCS_ROOT.resolve("alerts/nlti-alerts.md");
        assertThat(Files.exists(alerts))
                .as("alerts/nlti-alerts.md must exist").isTrue();
        assertThat(sizeOf(alerts))
                .as("alerts/nlti-alerts.md must be non-empty").isGreaterThan(0L);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
