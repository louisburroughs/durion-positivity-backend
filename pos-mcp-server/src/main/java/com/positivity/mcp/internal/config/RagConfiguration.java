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
            @Value("${mcp.rag.table-name:mcp_document_embedding}") String tableName,
            @Value("${mcp.rag.dimension:768}") int dimension,
            @Value("${mcp.rag.create-table:false}") boolean createTable) {
        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .vectorTableName(tableName)
                .dimensions(dimension)
                .initializeSchema(createTable)
                .build();
    }
}
