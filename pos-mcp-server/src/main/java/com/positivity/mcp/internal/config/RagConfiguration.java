package com.positivity.mcp.internal.config;

import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("alpha")
public class RagConfiguration {

    @Bean
    public PgVectorStore embeddingStore(
            @NonNull DataSource dataSource,
            @NonNull EmbeddingModel embeddingModel,
            @NonNull RagEmbeddingSettings embeddingSettings,
            @Value("${mcp.rag.table-name:mcp_document_embedding}") String tableName,
            @Value("${mcp.rag.create-table:false}") boolean createTable) {
        // Gate 5 (G5.4, #1194): the settings bean selects the 768 base table or the V31 1024 view
        // (PgVectorStore hardcodes the column name "embedding"; the view aliases embedding_1024).
        if (createTable && !RagEmbeddingSettings.COLUMN_768.equals(embeddingSettings.embeddingColumn())) {
            throw new IllegalStateException(
                    "mcp.rag.create-table=true is only supported with mcp.rag.embedding-column=embedding; "
                            + "the embedding_1024 path targets the V31 view, which Flyway owns.");
        }
        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .vectorTableName(embeddingSettings.vectorTableFor(tableName))
                .dimensions(embeddingSettings.dimension())
                .initializeSchema(createTable)
                .build();
    }
}
