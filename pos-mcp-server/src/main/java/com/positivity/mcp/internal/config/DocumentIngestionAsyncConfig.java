package com.positivity.mcp.internal.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentIngestionAsyncConfig {

    @Bean(name = "documentIngestionExecutor", destroyMethod = "close")
    public ExecutorService documentIngestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
