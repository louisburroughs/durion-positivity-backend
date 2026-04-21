package com.positivity.mcp.internal.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentIngestionAsyncConfig {

    @Bean(name = "documentIngestionExecutor", destroyMethod = "close")
    public ExecutorService documentIngestionExecutor(
            @Value("${mcp.rag.ingestion.max-concurrency:${MCP_RAG_INGESTION_MAX_CONCURRENCY:2}}") int maxConcurrency) {
        int sanitizedMaxConcurrency = Math.max(1, maxConcurrency);
        ThreadFactory threadFactory =
                Thread.ofVirtual().name("mcp-rag-ingestion-", 0).factory();
        return Executors.newFixedThreadPool(sanitizedMaxConcurrency, threadFactory);
    }
}
