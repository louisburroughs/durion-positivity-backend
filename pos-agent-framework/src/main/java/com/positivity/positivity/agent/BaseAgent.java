package com.positivity.positivity.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base implementation of the Agent interface providing common functionality
 */
public abstract class BaseAgent implements Agent {
    
    private static final Logger logger = LoggerFactory.getLogger(BaseAgent.class);
    
    private final String id;
    private final String name;
    private final String domain;
    private final Set<String> capabilities;
    private final Set<String> dependencies;
    private final AgentPerformanceSpec performanceSpec;
    
    // Metrics tracking
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicReference<Duration> averageResponseTime = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<Duration> maxResponseTime = new AtomicReference<>(Duration.ZERO);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicReference<AgentHealthStatus> healthStatus = new AtomicReference<>();
    
    protected BaseAgent(String id, String name, String domain, Set<String> capabilities, 
                       Set<String> dependencies, AgentPerformanceSpec performanceSpec) {
        this.id = id;
        this.name = name;
        this.domain = domain;
        this.capabilities = Set.copyOf(capabilities);
        this.dependencies = Set.copyOf(dependencies);
        this.performanceSpec = performanceSpec;
        this.healthStatus.set(AgentHealthStatus.healthy(id));
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDomain() {
        return domain;
    }
    
    @Override
    public Set<String> getCapabilities() {
        return capabilities;
    }
    
    @Override
    public Set<String> getDependencies() {
        return dependencies;
    }
    
    @Override
    public AgentPerformanceSpec getPerformanceSpec() {
        return performanceSpec;
    }
    
    @Override
    public CompletableFuture<AgentGuidanceResponse> provideGuidance(AgentConsultationRequest request) {
        if (!canHandle(request)) {
            return CompletableFuture.completedFuture(
                AgentGuidanceResponse.failure(request.requestId(), id, 
                    "Agent cannot handle request for domain: " + request.domain(), Duration.ZERO)
            );
        }
        
        if (activeRequests.get() >= performanceSpec.maxConcurrentRequests()) {
            return CompletableFuture.completedFuture(
                AgentGuidanceResponse.failure(request.requestId(), id, 
                    "Agent at maximum capacity", Duration.ZERO)
            );
        }
        
        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            activeRequests.incrementAndGet();
            totalRequests.incrementAndGet();
            
            try {
                logger.debug("Processing guidance request {} for domain {}", request.requestId(), request.domain());
                
                AgentGuidanceResponse response = processGuidanceRequest(request);
                
                Duration processingTime = Duration.between(start, Instant.now());
                updateMetrics(processingTime, true);
                
                if (processingTime.compareTo(performanceSpec.responseTime()) > 0) {
                    logger.warn("Agent {} exceeded response time threshold: {} > {}", 
                        id, processingTime, performanceSpec.responseTime());
                    updateHealthStatus(AgentHealthStatus.degraded(id, "Response time threshold exceeded"));
                } else {
                    updateHealthStatus(AgentHealthStatus.healthy(id));
                }
                
                return response;
                
            } catch (Exception e) {
                logger.error("Error processing guidance request {}", request.requestId(), e);
                Duration processingTime = Duration.between(start, Instant.now());
                updateMetrics(processingTime, false);
                updateHealthStatus(AgentHealthStatus.unhealthy(id, "Processing error: " + e.getMessage()));
                
                return AgentGuidanceResponse.failure(request.requestId(), id, 
                    "Internal error: " + e.getMessage(), processingTime);
            } finally {
                activeRequests.decrementAndGet();
            }
        });
    }
    
    /**
     * Abstract method for subclasses to implement their specific guidance logic
     */
    protected abstract AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request);
    
    @Override
    public boolean canHandle(AgentConsultationRequest request) {
        return domain.equals(request.domain()) || 
               capabilities.stream().anyMatch(cap -> request.query().toLowerCase().contains(cap.toLowerCase()));
    }
    
    @Override
    public AgentHealthStatus getHealthStatus() {
        return healthStatus.get();
    }
    
    @Override
    public boolean isAvailable() {
        return getHealthStatus().isAvailable() && 
               activeRequests.get() < performanceSpec.maxConcurrentRequests();
    }
    
    @Override
    public AgentMetrics getMetrics() {
        return new AgentMetrics(
            id,
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get(),
            averageResponseTime.get(),
            maxResponseTime.get(),
            calculateCurrentAccuracy(),
            calculateAvailability(),
            activeRequests.get(),
            Instant.now()
        );
    }
    
    private void updateMetrics(Duration processingTime, boolean success) {
        if (success) {
            successfulRequests.incrementAndGet();
        } else {
            failedRequests.incrementAndGet();
        }
        
        // Update average response time (simple moving average)
        Duration currentAvg = averageResponseTime.get();
        long totalReqs = totalRequests.get();
        Duration newAvg = currentAvg.multipliedBy(totalReqs - 1).plus(processingTime).dividedBy(totalReqs);
        averageResponseTime.set(newAvg);
        
        // Update max response time
        maxResponseTime.updateAndGet(current -> 
            processingTime.compareTo(current) > 0 ? processingTime : current);
    }
    
    private void updateHealthStatus(AgentHealthStatus status) {
        healthStatus.set(status);
    }
    
    private double calculateCurrentAccuracy() {
        long total = totalRequests.get();
        if (total == 0) return 1.0;
        return (double) successfulRequests.get() / total;
    }
    
    private double calculateAvailability() {
        // Simple availability calculation based on health status
        return getHealthStatus().isAvailable() ? 1.0 : 0.0;
    }
}