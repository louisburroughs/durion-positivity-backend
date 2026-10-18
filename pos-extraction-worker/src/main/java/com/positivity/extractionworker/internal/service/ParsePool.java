package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The bounded pool of parse slots. A call that finds none free is not queued: it is answered 503
 * {@code WORKER_BUSY} at once and the caller backs off (S26 ruling 5). Each admitted call owns one child
 * JVM, so the pool size bounds the container's memory.
 */
@Component
public class ParsePool {

    private final Semaphore permits;
    private final int capacity;

    public ParsePool(@NonNull ExtractionWorkerProperties properties, @NonNull MeterRegistry registry) {
        this.capacity = Math.max(1, properties.maxConcurrentParses());
        this.permits = new Semaphore(capacity);
        registry.gauge("extraction.pool.in_use", this, ParsePool::inUse);
    }

    /** Takes a slot without waiting; false when every slot is in use. */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int inUse() {
        return capacity - permits.availablePermits();
    }

    public int capacity() {
        return capacity;
    }
}
