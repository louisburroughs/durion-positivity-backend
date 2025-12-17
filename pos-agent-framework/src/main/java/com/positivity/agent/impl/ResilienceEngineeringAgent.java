package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Resilience Engineering Agent - Specialized expertise for system reliability
 * patterns
 * Provides guidance on circuit breakers, retry mechanisms, and chaos
 * engineering
 * 
 * Validates: Requirements REQ-015.1, REQ-015.2, REQ-015.3, REQ-015.4, REQ-015.5
 */
@Component
public class ResilienceEngineeringAgent extends BaseAgent {

    public ResilienceEngineeringAgent() {
        super(
                "resilience-engineering-agent",
                "Resilience Engineering Agent",
                "resilience",
                Set.of("circuit-breaker", "hystrix", "resilience4j", "spring-cloud-circuit-breaker",
                        "retry-patterns", "exponential-backoff", "bulkhead-patterns", "chaos-engineering",
                        "chaos-monkey", "failure-injection", "health-monitoring", "system-reliability"),
                Set.of("observability-agent", "testing-agent"), // Depends on monitoring and testing
                AgentPerformanceSpec.defaultSpec());
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        String guidance = generateResilienceGuidance(request);
        List<String> recommendations = generateResilienceRecommendations(request);

        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                guidance,
                0.93, // High confidence for resilience guidance
                recommendations,
                Duration.ofMillis(190));
    }

    private String generateResilienceGuidance(AgentConsultationRequest request) {
        String baseGuidance = "Resilience Engineering Guidance for " + request.domain() + ":\n\n";
        String query = request.query().toLowerCase();

        // Circuit Breaker Implementation (REQ-015.1)
        if (query.contains("circuit breaker") || query.contains("hystrix") ||
                query.contains("resilience4j") || query.contains("spring cloud circuit breaker")) {
            baseGuidance += generateCircuitBreakerGuidance(request);
        }

        // Retry Mechanisms (REQ-015.2)
        else if (query.contains("retry") || query.contains("exponential backoff") ||
                query.contains("jitter") || query.contains("retry limit")) {
            baseGuidance += generateRetryMechanismGuidance(request);
        }

        // Bulkhead Patterns (REQ-015.3)
        else if (query.contains("bulkhead") || query.contains("thread pool") ||
                query.contains("resource isolation") || query.contains("resource partitioning")) {
            baseGuidance += generateBulkheadPatternGuidance(request);
        }

        // Chaos Engineering (REQ-015.4)
        else if (query.contains("chaos engineering") || query.contains("chaos monkey") ||
                query.contains("failure injection") || query.contains("resilience testing")) {
            baseGuidance += generateChaosEngineeringGuidance(request);
        }

        // System Health Monitoring (REQ-015.5)
        else if (query.contains("health check") || query.contains("health monitoring") ||
                query.contains("circuit breaker metrics") || query.contains("failure rate")) {
            baseGuidance += generateHealthMonitoringGuidance(request);
        }

        // General resilience guidance
        else {
            baseGuidance += generateGeneralResilienceGuidance(request);
        }

        return baseGuidance;
    }

    private String generateCircuitBreakerGuidance(AgentConsultationRequest request) {
        String query = request.query().toLowerCase();

        if (query.contains("resilience4j")) {
            return generateResilience4jGuidance();
        } else if (query.contains("hystrix")) {
            return generateHystrixGuidance();
        } else if (query.contains("spring cloud")) {
            return generateSpringCloudCircuitBreakerGuidance();
        } else {
            return generateGeneralCircuitBreakerGuidance();
        }
    }

    private String generateResilience4jGuidance() {
        return """
                Resilience4j Circuit Breaker Implementation:

                1. Dependency Configuration:
                   ```xml
                   <dependency>
                       <groupId>io.github.resilience4j</groupId>
                       <artifactId>resilience4j-spring-boot2</artifactId>
                       <version>2.1.0</version>
                   </dependency>
                   <dependency>
                       <groupId>io.github.resilience4j</groupId>
                       <artifactId>resilience4j-micrometer</artifactId>
                       <version>2.1.0</version>
                   </dependency>
                   ```

                2. Circuit Breaker Configuration:
                   ```yaml
                   resilience4j:
                     circuitbreaker:
                       instances:
                         orderService:
                           failure-rate-threshold: 50
                           minimum-number-of-calls: 10
                           sliding-window-size: 20
                           sliding-window-type: COUNT_BASED
                           wait-duration-in-open-state: 30s
                           permitted-number-of-calls-in-half-open-state: 5
                           automatic-transition-from-open-to-half-open-enabled: true
                           record-exceptions:
                             - java.net.ConnectException
                             - java.util.concurrent.TimeoutException
                           ignore-exceptions:
                             - java.lang.IllegalArgumentException

                         paymentService:
                           failure-rate-threshold: 60
                           minimum-number-of-calls: 5
                           sliding-window-size: 10
                           wait-duration-in-open-state: 60s
                   ```

                3. Service Implementation with Circuit Breaker:
                   ```java
                   @Service
                   public class OrderService {

                       private final PaymentServiceClient paymentClient;
                       private final CircuitBreaker circuitBreaker;

                       public OrderService(PaymentServiceClient paymentClient) {
                           this.paymentClient = paymentClient;
                           this.circuitBreaker = CircuitBreaker.ofDefaults("orderService");
                       }

                       @CircuitBreaker(name = "orderService", fallbackMethod = "fallbackProcessPayment")
                       public PaymentResult processPayment(PaymentRequest request) {
                           return paymentClient.processPayment(request);
                       }

                       public PaymentResult fallbackProcessPayment(PaymentRequest request, Exception ex) {
                           log.warn("Payment service unavailable, using fallback: {}", ex.getMessage());
                           return PaymentResult.builder()
                               .status(PaymentStatus.PENDING)
                               .message("Payment queued for later processing")
                               .build();
                       }
                   }
                   ```

                4. Programmatic Circuit Breaker Usage:
                   ```java
                   @Service
                   public class InventoryService {

                       private final CircuitBreaker circuitBreaker;
                       private final InventoryClient inventoryClient;

                       public InventoryService(CircuitBreakerRegistry registry) {
                           this.circuitBreaker = registry.circuitBreaker("inventoryService");
                           this.circuitBreaker.getEventPublisher()
                               .onStateTransition(event ->
                                   log.info("Circuit breaker state transition: {}", event));
                       }

                       public Optional<InventoryItem> checkInventory(String productId) {
                           Supplier<Optional<InventoryItem>> decoratedSupplier =
                               CircuitBreaker.decorateSupplier(circuitBreaker, () ->
                                   inventoryClient.getInventory(productId));

                           try {
                               return decoratedSupplier.get();
                           } catch (CallNotPermittedException e) {
                               log.warn("Circuit breaker is open for inventory service");
                               return Optional.empty();
                           }
                       }
                   }
                   ```

                5. Metrics and Monitoring:
                   ```java
                   @Component
                   public class CircuitBreakerMetrics {

                       @EventListener
                       public void onCircuitBreakerEvent(CircuitBreakerOnStateTransitionEvent event) {
                           CircuitBreaker.State fromState = event.getStateTransition().getFromState();
                           CircuitBreaker.State toState = event.getStateTransition().getToState();

                           log.info("Circuit breaker '{}' transitioned from {} to {}",
                               event.getCircuitBreakerName(), fromState, toState);

                           // Send metrics to monitoring system
                           meterRegistry.counter("circuit.breaker.state.transition",
                               "name", event.getCircuitBreakerName(),
                               "from", fromState.toString(),
                               "to", toState.toString()).increment();
                       }
                   }
                   ```
                """;
    }

    private String generateHystrixGuidance() {
        return """
                Hystrix Circuit Breaker Implementation (Legacy):

                Note: Hystrix is in maintenance mode. Consider migrating to Resilience4j.

                1. Dependency Configuration:
                   ```xml
                   <dependency>
                       <groupId>org.springframework.cloud</groupId>
                       <artifactId>spring-cloud-starter-netflix-hystrix</artifactId>
                   </dependency>
                   ```

                2. Enable Hystrix:
                   ```java
                   @SpringBootApplication
                   @EnableHystrix
                   public class PosApplication {
                       public static void main(String[] args) {
                           SpringApplication.run(PosApplication.class, args);
                       }
                   }
                   ```

                3. Hystrix Command Implementation:
                   ```java
                   @Service
                   public class OrderService {

                       @HystrixCommand(
                           fallbackMethod = "fallbackGetOrder",
                           commandProperties = {
                               @HystrixProperty(name = "execution.isolation.thread.timeoutInMilliseconds", value = "3000"),
                               @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "10"),
                               @HystrixProperty(name = "circuitBreaker.errorThresholdPercentage", value = "50"),
                               @HystrixProperty(name = "circuitBreaker.sleepWindowInMilliseconds", value = "30000")
                           }
                       )
                       public Order getOrder(String orderId) {
                           return orderClient.getOrder(orderId);
                       }

                       public Order fallbackGetOrder(String orderId) {
                           return Order.builder()
                               .id(orderId)
                               .status(OrderStatus.UNKNOWN)
                               .message("Order service temporarily unavailable")
                               .build();
                       }
                   }
                   ```

                4. Migration Path to Resilience4j:
                   ```java
                   // Old Hystrix code
                   @HystrixCommand(fallbackMethod = "fallback")
                   public String callExternalService() {
                       return externalService.call();
                   }

                   // New Resilience4j code
                   @CircuitBreaker(name = "externalService", fallbackMethod = "fallback")
                   public String callExternalService() {
                       return externalService.call();
                   }
                   ```
                """;
    }

    private String generateSpringCloudCircuitBreakerGuidance() {
        return """
                Spring Cloud Circuit Breaker Implementation:

                1. Dependency Configuration:
                   ```xml
                   <dependency>
                       <groupId>org.springframework.cloud</groupId>
                       <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
                   </dependency>
                   ```

                2. Circuit Breaker Factory Configuration:
                   ```java
                   @Configuration
                   public class CircuitBreakerConfig {

                       @Bean
                       public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
                           return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                               .circuitBreakerConfig(CircuitBreakerConfig.custom()
                                   .slidingWindowSize(10)
                                   .minimumNumberOfCalls(5)
                                   .failureRateThreshold(50.0f)
                                   .waitDurationInOpenState(Duration.ofSeconds(30))
                                   .build())
                               .timeLimiterConfig(TimeLimiterConfig.custom()
                                   .timeoutDuration(Duration.ofSeconds(3))
                                   .build())
                               .build());
                       }
                   }
                   ```

                3. Service Implementation:
                   ```java
                   @Service
                   public class ProductService {

                       private final CircuitBreakerFactory circuitBreakerFactory;
                       private final ProductClient productClient;

                       public ProductService(CircuitBreakerFactory circuitBreakerFactory,
                                           ProductClient productClient) {
                           this.circuitBreakerFactory = circuitBreakerFactory;
                           this.productClient = productClient;
                       }

                       public Product getProduct(String productId) {
                           CircuitBreaker circuitBreaker = circuitBreakerFactory.create("productService");

                           return circuitBreaker.executeSupplier(() ->
                               productClient.getProduct(productId));
                       }

                       public Product getProductWithFallback(String productId) {
                           CircuitBreaker circuitBreaker = circuitBreakerFactory.create("productService");

                           return circuitBreaker.executeSupplier(
                               () -> productClient.getProduct(productId),
                               throwable -> {
                                   log.warn("Product service unavailable: {}", throwable.getMessage());
                                   return Product.builder()
                                       .id(productId)
                                       .name("Product Unavailable")
                                       .available(false)
                                       .build();
                               }
                           );
                       }
                   }
                   ```
                """;
    }

    private String generateRetryMechanismGuidance(AgentConsultationRequest request) {
        return """
                Retry Mechanism Implementation:

                1. Spring Retry Configuration:
                   ```xml
                   <dependency>
                       <groupId>org.springframework.retry</groupId>
                       <artifactId>spring-retry</artifactId>
                   </dependency>
                   <dependency>
                       <groupId>org.springframework</groupId>
                       <artifactId>spring-aspects</artifactId>
                   </dependency>
                   ```

                2. Enable Retry and Configuration:
                   ```java
                   @Configuration
                   @EnableRetry
                   public class RetryConfig {

                       @Bean
                       public RetryTemplate retryTemplate() {
                           return RetryTemplate.builder()
                               .maxAttempts(3)
                               .exponentialBackoff(1000, 2, 10000)
                               .retryOn(ConnectException.class)
                               .retryOn(SocketTimeoutException.class)
                               .build();
                       }
                   }
                   ```

                3. Declarative Retry with Annotations:
                   ```java
                   @Service
                   public class ExternalApiService {

                       @Retryable(
                           value = {ConnectException.class, SocketTimeoutException.class},
                           maxAttempts = 3,
                           backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
                       )
                       public ApiResponse callExternalApi(ApiRequest request) {
                           return externalApiClient.call(request);
                       }

                       @Recover
                       public ApiResponse recover(ConnectException ex, ApiRequest request) {
                           log.error("Failed to call external API after retries: {}", ex.getMessage());
                           return ApiResponse.builder()
                               .status("ERROR")
                               .message("Service temporarily unavailable")
                               .build();
                       }
                   }
                   ```

                4. Programmatic Retry with RetryTemplate:
                   ```java
                   @Service
                   public class DatabaseService {

                       private final RetryTemplate retryTemplate;

                       public void saveWithRetry(Entity entity) {
                           retryTemplate.execute(context -> {
                               log.info("Attempt {} to save entity", context.getRetryCount() + 1);
                               return entityRepository.save(entity);
                           });
                       }
                   }
                   ```

                5. Resilience4j Retry Configuration:
                   ```yaml
                   resilience4j:
                     retry:
                       instances:
                         databaseService:
                           max-attempts: 3
                           wait-duration: 1s
                           exponential-backoff-multiplier: 2
                           retry-exceptions:
                             - java.sql.SQLException
                             - org.springframework.dao.DataAccessException
                           ignore-exceptions:
                             - java.lang.IllegalArgumentException
                   ```

                6. Advanced Retry Patterns:
                   ```java
                   @Service
                   public class AdvancedRetryService {

                       // Retry with jitter to avoid thundering herd
                       @Retryable(
                           maxAttempts = 5,
                           backoff = @Backoff(
                               delay = 1000,
                               multiplier = 1.5,
                               maxDelay = 30000,
                               random = true  // Adds jitter
                           )
                       )
                       public Result processWithJitter() {
                           return externalService.process();
                       }

                       // Conditional retry based on response
                       public Result processWithConditionalRetry() {
                           return retryTemplate.execute(context -> {
                               Result result = externalService.process();

                               // Retry on specific business conditions
                               if (result.getStatus() == Status.RATE_LIMITED) {
                                   throw new RetryableException("Rate limited, retrying...");
                               }

                               return result;
                           });
                       }
                   }
                   ```
                """;
    }

    private String generateBulkheadPatternGuidance(AgentConsultationRequest request) {
        return """
                Bulkhead Pattern Implementation:

                1. Thread Pool Isolation:
                   ```java
                   @Configuration
                   public class BulkheadConfig {

                       @Bean("orderProcessingExecutor")
                       public ThreadPoolTaskExecutor orderProcessingExecutor() {
                           ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                           executor.setCorePoolSize(5);
                           executor.setMaxPoolSize(10);
                           executor.setQueueCapacity(25);
                           executor.setThreadNamePrefix("order-processing-");
                           executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
                           executor.initialize();
                           return executor;
                       }

                       @Bean("paymentProcessingExecutor")
                       public ThreadPoolTaskExecutor paymentProcessingExecutor() {
                           ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                           executor.setCorePoolSize(3);
                           executor.setMaxPoolSize(8);
                           executor.setQueueCapacity(15);
                           executor.setThreadNamePrefix("payment-processing-");
                           executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
                           executor.initialize();
                           return executor;
                       }
                   }
                   ```

                2. Service Implementation with Bulkhead:
                   ```java
                   @Service
                   public class OrderProcessingService {

                       @Async("orderProcessingExecutor")
                       @Bulkhead(name = "orderProcessing", type = Bulkhead.Type.THREADPOOL)
                       public CompletableFuture<OrderResult> processOrder(Order order) {
                           // Order processing logic isolated in dedicated thread pool
                           OrderResult result = performOrderProcessing(order);
                           return CompletableFuture.completedFuture(result);
                       }

                       @Async("paymentProcessingExecutor")
                       @Bulkhead(name = "paymentProcessing", type = Bulkhead.Type.THREADPOOL)
                       public CompletableFuture<PaymentResult> processPayment(Payment payment) {
                           // Payment processing isolated in separate thread pool
                           PaymentResult result = performPaymentProcessing(payment);
                           return CompletableFuture.completedFuture(result);
                       }
                   }
                   ```

                3. Resilience4j Bulkhead Configuration:
                   ```yaml
                   resilience4j:
                     bulkhead:
                       instances:
                         orderService:
                           max-concurrent-calls: 10
                           max-wait-duration: 1s
                         paymentService:
                           max-concurrent-calls: 5
                           max-wait-duration: 500ms
                     thread-pool-bulkhead:
                       instances:
                         orderProcessing:
                           core-thread-pool-size: 5
                           max-thread-pool-size: 10
                           queue-capacity: 25
                           keep-alive-duration: 60s
                         paymentProcessing:
                           core-thread-pool-size: 3
                           max-thread-pool-size: 8
                           queue-capacity: 15
                   ```

                4. Resource Partitioning:
                   ```java
                   @Service
                   public class ResourcePartitioningService {

                       // Separate connection pools for different operations
                       @Qualifier("readOnlyDataSource")
                       private final DataSource readOnlyDataSource;

                       @Qualifier("writeDataSource")
                       private final DataSource writeDataSource;

                       @Qualifier("reportingDataSource")
                       private final DataSource reportingDataSource;

                       // Separate Redis connections for different use cases
                       @Qualifier("cacheRedisTemplate")
                       private final RedisTemplate<String, Object> cacheRedis;

                       @Qualifier("sessionRedisTemplate")
                       private final RedisTemplate<String, Object> sessionRedis;

                       public void performReadOperation() {
                           // Use read-only connection pool
                           jdbcTemplate.setDataSource(readOnlyDataSource);
                           // Perform read operations
                       }

                       public void performWriteOperation() {
                           // Use write connection pool
                           jdbcTemplate.setDataSource(writeDataSource);
                           // Perform write operations
                       }
                   }
                   ```

                5. Circuit Breaker with Bulkhead:
                   ```java
                   @Service
                   public class ResilientService {

                       @CircuitBreaker(name = "externalService")
                       @Bulkhead(name = "externalService", type = Bulkhead.Type.SEMAPHORE)
                       @TimeLimiter(name = "externalService")
                       public CompletableFuture<String> callExternalService() {
                           return CompletableFuture.supplyAsync(() -> {
                               // External service call with multiple resilience patterns
                               return externalServiceClient.call();
                           });
                       }
                   }
                   ```
                """;
    }

    private String generateChaosEngineeringGuidance(AgentConsultationRequest request) {
        return """
                Chaos Engineering Implementation:

                1. Chaos Monkey for Spring Boot:
                   ```xml
                   <dependency>
                       <groupId>de.codecentric</groupId>
                       <artifactId>chaos-monkey-spring-boot</artifactId>
                       <version>2.7.0</version>
                   </dependency>
                   ```

                2. Chaos Monkey Configuration:
                   ```yaml
                   chaos:
                     monkey:
                       enabled: true
                       watcher:
                         controller: true
                         restController: true
                         service: true
                         repository: true
                         component: true
                       assaults:
                         level: 5
                         latencyRangeStart: 1000
                         latencyRangeEnd: 3000
                         latencyActive: true
                         exceptionsActive: true
                         killApplicationActive: false
                         memoryActive: true
                         memoryMillisecondsHoldFilledMemory: 90000
                         memoryMillisecondsWaitNextIncrease: 1000
                         memoryFillIncrementFraction: 0.15
                         memoryFillTargetFraction: 0.25
                   ```

                3. Custom Chaos Experiments:
                   ```java
                   @Component
                   public class CustomChaosExperiments {

                       private final Random random = new Random();

                       @EventListener
                       @ConditionalOnProperty("chaos.experiments.enabled")
                       public void simulateNetworkLatency(ApplicationReadyEvent event) {
                           // Simulate network latency randomly
                           if (random.nextDouble() < 0.1) { // 10% chance
                               try {
                                   Thread.sleep(random.nextInt(2000) + 1000); // 1-3 seconds
                               } catch (InterruptedException e) {
                                   Thread.currentThread().interrupt();
                               }
                           }
                       }

                       @Scheduled(fixedRate = 300000) // Every 5 minutes
                       @ConditionalOnProperty("chaos.experiments.database-failures")
                       public void simulateDatabaseFailures() {
                           if (random.nextDouble() < 0.05) { // 5% chance
                               // Temporarily disable database connections
                               log.warn("Chaos experiment: Simulating database failure");
                               // Implementation to simulate DB failure
                           }
                       }
                   }
                   ```

                4. Failure Injection Patterns:
                   ```java
                   @Service
                   public class FailureInjectionService {

                       @Value("${chaos.failure-injection.enabled:false}")
                       private boolean failureInjectionEnabled;

                       public void injectRandomFailures() {
                           if (!failureInjectionEnabled) return;

                           double failureRate = 0.02; // 2% failure rate
                           if (Math.random() < failureRate) {
                               throw new ChaosException("Simulated failure for resilience testing");
                           }
                       }

                       public void injectLatency() {
                           if (!failureInjectionEnabled) return;

                           if (Math.random() < 0.1) { // 10% chance
                               try {
                                   Thread.sleep(ThreadLocalRandom.current().nextInt(500, 2000));
                               } catch (InterruptedException e) {
                                   Thread.currentThread().interrupt();
                               }
                           }
                       }
                   }
                   ```

                5. Chaos Engineering Best Practices:
                   ```java
                   @Component
                   public class ChaosEngineeringPrinciples {

                       // 1. Start with a hypothesis
                       public void defineHypothesis() {
                           // "The system should remain available even if the payment service fails"
                       }

                       // 2. Vary real-world events
                       public void simulateRealWorldFailures() {
                           // Network partitions, hardware failures, software bugs
                       }

                       // 3. Run experiments in production
                       @ConditionalOnProperty("spring.profiles.active=prod")
                       public void runProductionExperiments() {
                           // Controlled experiments in production environment
                       }

                       // 4. Automate experiments
                       @Scheduled(cron = "0 0 2 * * MON") // Every Monday at 2 AM
                       public void automatedChaosExperiment() {
                           // Automated weekly chaos experiments
                       }

                       // 5. Minimize blast radius
                       public void limitImpact() {
                           // Start small, gradually increase scope
                       }
                   }
                   ```

                6. Chaos Engineering Metrics:
                   ```java
                   @Component
                   public class ChaosMetrics {

                       private final MeterRegistry meterRegistry;

                       @EventListener
                       public void recordChaosEvent(ChaosEvent event) {
                           meterRegistry.counter("chaos.experiments.executed",
                               "type", event.getType(),
                               "success", String.valueOf(event.isSuccessful()))
                               .increment();
                       }

                       public void recordSystemRecoveryTime(Duration recoveryTime) {
                           meterRegistry.timer("chaos.recovery.time").record(recoveryTime);
                       }
                   }
                   ```
                """;
    }

    private String generateHealthMonitoringGuidance(AgentConsultationRequest request) {
        return """
                System Health Monitoring and Circuit Breaker Metrics:

                1. Health Check Implementation:
                   ```java
                   @Component
                   public class CircuitBreakerHealthIndicator implements HealthIndicator {

                       private final CircuitBreakerRegistry circuitBreakerRegistry;

                       @Override
                       public Health health() {
                           Health.Builder builder = Health.up();

                           circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {
                               CircuitBreaker.State state = circuitBreaker.getState();
                               CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

                               builder.withDetail(circuitBreaker.getName(), Map.of(
                                   "state", state.toString(),
                                   "failureRate", metrics.getFailureRate(),
                                   "numberOfCalls", metrics.getNumberOfBufferedCalls(),
                                   "numberOfFailedCalls", metrics.getNumberOfFailedCalls()
                               ));

                               if (state == CircuitBreaker.State.OPEN) {
                                   builder.down();
                               }
                           });

                           return builder.build();
                       }
                   }
                   ```

                2. Custom Health Indicators:
                   ```java
                   @Component
                   public class DatabaseHealthIndicator implements HealthIndicator {

                       private final DataSource dataSource;

                       @Override
                       public Health health() {
                           try (Connection connection = dataSource.getConnection()) {
                               if (connection.isValid(3)) {
                                   return Health.up()
                                       .withDetail("database", "Available")
                                       .withDetail("validationQuery", "SELECT 1")
                                       .build();
                               }
                           } catch (SQLException e) {
                               return Health.down()
                                   .withDetail("database", "Unavailable")
                                   .withDetail("error", e.getMessage())
                                   .build();
                           }

                           return Health.down()
                               .withDetail("database", "Connection validation failed")
                               .build();
                       }
                   }

                   @Component
                   public class ExternalServiceHealthIndicator implements HealthIndicator {

                       private final WebClient webClient;

                       @Override
                       public Health health() {
                           try {
                               String response = webClient.get()
                                   .uri("/health")
                                   .retrieve()
                                   .bodyToMono(String.class)
                                   .timeout(Duration.ofSeconds(3))
                                   .block();

                               return Health.up()
                                   .withDetail("externalService", "Available")
                                   .withDetail("response", response)
                                   .build();
                           } catch (Exception e) {
                               return Health.down()
                                   .withDetail("externalService", "Unavailable")
                                   .withDetail("error", e.getMessage())
                                   .build();
                           }
                       }
                   }
                   ```

                3. Circuit Breaker Metrics Collection:
                   ```java
                   @Component
                   public class ResilienceMetricsCollector {

                       private final MeterRegistry meterRegistry;
                       private final CircuitBreakerRegistry circuitBreakerRegistry;

                       @PostConstruct
                       public void registerMetrics() {
                           circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {

                               // Register state gauge
                               Gauge.builder("circuit.breaker.state")
                                   .tag("name", circuitBreaker.getName())
                                   .register(meterRegistry, circuitBreaker, cb ->
                                       cb.getState() == CircuitBreaker.State.CLOSED ? 0 :
                                       cb.getState() == CircuitBreaker.State.OPEN ? 1 : 2);

                               // Register failure rate gauge
                               Gauge.builder("circuit.breaker.failure.rate")
                                   .tag("name", circuitBreaker.getName())
                                   .register(meterRegistry, circuitBreaker, cb ->
                                       cb.getMetrics().getFailureRate());

                               // Register call counters
                               circuitBreaker.getEventPublisher()
                                   .onCallNotPermitted(event ->
                                       meterRegistry.counter("circuit.breaker.calls.rejected",
                                           "name", circuitBreaker.getName()).increment())
                                   .onSuccess(event ->
                                       meterRegistry.counter("circuit.breaker.calls.successful",
                                           "name", circuitBreaker.getName()).increment())
                                   .onError(event ->
                                       meterRegistry.counter("circuit.breaker.calls.failed",
                                           "name", circuitBreaker.getName()).increment());
                           });
                       }
                   }
                   ```

                4. Failure Rate Monitoring:
                   ```java
                   @Component
                   public class FailureRateMonitor {

                       private final MeterRegistry meterRegistry;

                       @Scheduled(fixedRate = 30000) // Every 30 seconds
                       public void monitorFailureRates() {
                           circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {
                               CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
                               float failureRate = metrics.getFailureRate();

                               // Alert if failure rate exceeds threshold
                               if (failureRate > 25.0f) {
                                   log.warn("High failure rate detected for {}: {}%",
                                       circuitBreaker.getName(), failureRate);

                                   // Send alert to monitoring system
                                   alertService.sendAlert(AlertLevel.WARNING,
                                       "High failure rate: " + circuitBreaker.getName());
                               }

                               // Record metrics
                               meterRegistry.gauge("service.failure.rate",
                                   Tags.of("service", circuitBreaker.getName()),
                                   failureRate);
                           });
                       }
                   }
                   ```

                5. Comprehensive Monitoring Dashboard:
                   ```yaml
                   # Prometheus metrics configuration
                   management:
                     endpoints:
                       web:
                         exposure:
                           include: health,info,metrics,prometheus
                     metrics:
                       export:
                         prometheus:
                           enabled: true
                       tags:
                         application: ${spring.application.name}
                         environment: ${spring.profiles.active}
                       distribution:
                         percentiles-histogram:
                           http.server.requests: true
                           resilience4j.circuitbreaker.calls: true
                   ```

                6. Alerting Rules:
                   ```java
                   @Component
                   public class ResilienceAlerting {

                       @EventListener
                       public void handleCircuitBreakerOpen(CircuitBreakerOnStateTransitionEvent event) {
                           if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                               alertService.sendAlert(AlertLevel.CRITICAL,
                                   "Circuit breaker opened: " + event.getCircuitBreakerName());
                           }
                       }

                       @EventListener
                       public void handleCircuitBreakerClosed(CircuitBreakerOnStateTransitionEvent event) {
                           if (event.getStateTransition().getToState() == CircuitBreaker.State.CLOSED) {
                               alertService.sendAlert(AlertLevel.INFO,
                                   "Circuit breaker recovered: " + event.getCircuitBreakerName());
                           }
                       }
                   }
                   ```
                """;
    }

    private String generateGeneralCircuitBreakerGuidance() {
        return """
                General Circuit Breaker Implementation Guidelines:

                1. Circuit Breaker Pattern Principles:
                   - **Closed State**: Normal operation, calls pass through
                   - **Open State**: Calls fail fast, no requests to failing service
                   - **Half-Open State**: Limited calls to test service recovery

                2. Configuration Best Practices:
                   - Set appropriate failure rate thresholds (typically 50-60%)
                   - Configure minimum number of calls before evaluation
                   - Set reasonable timeout for open state (30-60 seconds)
                   - Define appropriate sliding window size

                3. Fallback Strategies:
                   - Return cached data when available
                   - Provide default or degraded functionality
                   - Queue requests for later processing
                   - Return user-friendly error messages

                4. Monitoring and Alerting:
                   - Track circuit breaker state transitions
                   - Monitor failure rates and response times
                   - Set up alerts for circuit breaker state changes
                   - Create dashboards for resilience metrics
                """;
    }

    private String generateGeneralResilienceGuidance(AgentConsultationRequest request) {
        return """
                Comprehensive Resilience Engineering Strategy:

                1. Resilience Patterns Overview:
                   - **Circuit Breaker**: Prevent cascading failures
                   - **Retry**: Handle transient failures
                   - **Bulkhead**: Isolate resources and failures
                   - **Timeout**: Prevent resource exhaustion
                   - **Rate Limiting**: Control request flow

                2. Implementation Strategy:
                   - Start with circuit breakers for external dependencies
                   - Add retry mechanisms for transient failures
                   - Implement bulkhead patterns for resource isolation
                   - Use chaos engineering to validate resilience
                   - Monitor and measure system resilience

                3. Best Practices:
                   - Design for failure from the beginning
                   - Implement graceful degradation
                   - Use observability to understand system behavior
                   - Test resilience patterns regularly
                   - Document failure scenarios and responses

                4. Resilience Testing:
                   - Unit tests for individual resilience patterns
                   - Integration tests for failure scenarios
                   - Chaos engineering for production validation
                   - Load testing under failure conditions
                   - Regular disaster recovery exercises
                """;
    }

    private List<String> generateResilienceRecommendations(AgentConsultationRequest request) {
        String query = request.query().toLowerCase();

        if (query.contains("circuit breaker")) {
            return List.of(
                    "Use Resilience4j for modern circuit breaker implementation",
                    "Configure appropriate failure thresholds and timeouts",
                    "Implement meaningful fallback mechanisms",
                    "Monitor circuit breaker state transitions and metrics");
        } else if (query.contains("retry")) {
            return List.of(
                    "Implement exponential backoff with jitter to avoid thundering herd",
                    "Set maximum retry limits to prevent infinite loops",
                    "Use different retry strategies for different failure types",
                    "Combine retry with circuit breaker for comprehensive resilience");
        } else if (query.contains("bulkhead")) {
            return List.of(
                    "Isolate critical resources with separate thread pools",
                    "Partition database connections by operation type",
                    "Use semaphore-based bulkheads for lightweight isolation",
                    "Monitor resource utilization across bulkheads");
        } else if (query.contains("chaos")) {
            return List.of(
                    "Start with controlled chaos experiments in non-production",
                    "Define clear hypotheses before running experiments",
                    "Gradually increase experiment scope and complexity",
                    "Automate chaos experiments as part of CI/CD pipeline");
        } else {
            return List.of(
                    "Implement multiple resilience patterns for comprehensive protection",
                    "Design systems with graceful degradation capabilities",
                    "Use chaos engineering to validate resilience assumptions",
                    "Monitor system behavior under failure conditions",
                    "Create runbooks for common failure scenarios and recovery procedures");
        }
    }
}