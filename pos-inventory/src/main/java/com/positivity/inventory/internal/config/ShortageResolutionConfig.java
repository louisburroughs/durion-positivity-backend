package com.positivity.inventory.internal.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortageResolutionConfig {

    @Bean(name = "shortageResolutionExecutor", destroyMethod = "close")
    public Executor shortageResolutionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
