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
        // #1207 (V33): the base table's embedding column is 1024-dim and PgVectorStore targets it
        // directly — the V31 view indirection is gone. RagEmbeddingSettings validates the config.
        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .vectorTableName(embeddingSettings.vectorTableFor(tableName))
                .dimensions(embeddingSettings.dimension())
                .initializeSchema(createTable)
                .build();
    }
}
