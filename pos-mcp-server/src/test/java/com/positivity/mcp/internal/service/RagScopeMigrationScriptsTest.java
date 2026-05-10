package com.positivity.mcp.internal.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class RagScopeMigrationScriptsTest {

    @Test
    void postgresMigrationBackfillsLegacyEmbeddingMetadataWithMasterScope() throws IOException {
        String sql = new String(
                Objects.requireNonNull(
                                getClass().getResourceAsStream("/db/migration/V16__backfill_embedding_rag_scope.sql"))
                        .readAllBytes(),
                UTF_8);

        assertThat(sql)
                .contains("UPDATE mcp_document_embedding")
                .contains("jsonb_set")
                .contains("rag_scope")
                .contains("'master'");
    }

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
