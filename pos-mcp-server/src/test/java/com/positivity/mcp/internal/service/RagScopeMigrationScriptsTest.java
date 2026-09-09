package com.positivity.mcp.internal.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The Postgres side of this history was flattened into {@code V1__baseline_mcp_server.sql}
 * (2026-09-09); the H2 chain still carries its own version numbers and this keeps that alignment
 * script present until the H2 profiles move to Testcontainers.
 */
class RagScopeMigrationScriptsTest {

    @Test
    void h2MigrationExistsForFlywayVersionAlignment() throws IOException {
        String sql = new String(
                Objects.requireNonNull(getClass()
                                .getResourceAsStream("/db/h2-migration/V16__backfill_embedding_rag_scope.sql"))
                        .readAllBytes(),
                UTF_8);

        assertThat(sql).contains("SELECT 1");
    }
}
