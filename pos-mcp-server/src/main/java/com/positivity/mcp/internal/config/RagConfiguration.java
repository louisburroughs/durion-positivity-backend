package com.positivity.mcp.internal.config;

import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration
@Profile("!test")
public class RagConfiguration {

  @Bean
  public PgVectorEmbeddingStore embeddingStore(
      @NonNull DataSource dataSource,
      @Value("${mcp.rag.table-name:mcp_document_embedding}") String tableName,
      @Value("${mcp.rag.dimension:768}") int dimension) {
    return PgVectorEmbeddingStore.datasourceBuilder()
        .datasource(dataSource)
        .table(tableName)
        .dimension(dimension)
        .createTable(true)
        .build();
  }
}
