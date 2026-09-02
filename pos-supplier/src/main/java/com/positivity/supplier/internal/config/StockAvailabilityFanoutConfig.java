package com.positivity.supplier.internal.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor for the product-keyed availability fan-out (#1637 decision 1).
 *
 * <p>Virtual threads, deliberately: each fan-out leg is one blocking vendor HTTP call, the natural
 * fit for a virtual thread, and the per-request concurrency is bounded by the number of enabled
 * STOCK_INQUIRY bindings a deployment configures — a handful of trading partners, not an unbounded
 * queue. The real ceilings on vendor pressure stay where they already live: the per-supplier
 * circuit breakers and the profile connect/read timeouts in {@code SupplierBaseClient}, and the
 * fan-out deadline in the service caps how long any of it can hold a page.
 */
@Configuration
public class StockAvailabilityFanoutConfig {

    @Bean(name = "stockAvailabilityFanoutExecutor", destroyMethod = "close")
    ExecutorService stockAvailabilityFanoutExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
