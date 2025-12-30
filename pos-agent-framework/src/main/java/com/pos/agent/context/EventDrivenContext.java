package com.pos.agent.context;

import java.time.Instant;
import java.util.*;

/**
 * Context model for event-driven architecture scenarios.
 * Tracks message brokers, event handlers, schemas, and event sourcing patterns.
 */
public class EventDrivenContext {
    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Message brokers and queues
    private final Set<String> messageBrokers;
    private final Map<String, Map<String, Object>> brokerConfigurations;
    private final Set<String> deadLetterQueues;
    private final Map<String, String> dlqConfigurations;

    // Event handling
    private final Set<String> eventHandlers;
    private final Map<String, String> handlerTypes;
    private final Set<String> eventStores;
    private final Map<String, String> eventSchemas;
    private final Set<String> eventSources;
    private final Set<String> eventConsumers;
    private final Set<String> idempotencyPatterns;
    private final Set<String> errorHandlingStrategies;
    private final Set<String> projections;

    // Patterns
    private final Set<String> sagas;
    private final Set<String> choreography;
    private final Set<String> orchestration;

    public EventDrivenContext(String contextId, String sessionId) {
        this.contextId = Objects.requireNonNull(contextId, "Context ID cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Session ID cannot be null");
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();

        this.messageBrokers = new LinkedHashSet<>();
        this.brokerConfigurations = new HashMap<>();
        this.deadLetterQueues = new LinkedHashSet<>();
        this.dlqConfigurations = new HashMap<>();

        this.eventHandlers = new LinkedHashSet<>();
        this.handlerTypes = new HashMap<>();
        this.eventStores = new LinkedHashSet<>();
        this.eventSchemas = new HashMap<>();
        this.eventSources = new LinkedHashSet<>();
        this.eventConsumers = new LinkedHashSet<>();
        this.idempotencyPatterns = new LinkedHashSet<>();
        this.errorHandlingStrategies = new LinkedHashSet<>();
        this.projections = new LinkedHashSet<>();

        this.sagas = new LinkedHashSet<>();
        this.choreography = new LinkedHashSet<>();
        this.orchestration = new LinkedHashSet<>();
    }

    // Getters
    public String getContextId() {
        return contextId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public Set<String> getMessageBrokers() {
        return new LinkedHashSet<>(messageBrokers);
    }

    public Map<String, Object> getBrokerConfigurations() {
        Map<String, Object> result = new HashMap<>();
        brokerConfigurations.forEach((key, value) -> result.put(key, new HashMap<>(value)));
        return result;
    }

    public Set<String> getDeadLetterQueues() {
        return new LinkedHashSet<>(dlqConfigurations.keySet());
    }

    public Map<String, String> getDeadLetterQueuesMap() {
        return new HashMap<>(dlqConfigurations);
    }

    public Set<String> getEventHandlers() {
        return new LinkedHashSet<>(eventHandlers);
    }

    public Set<String> getEventStores() {
        return new LinkedHashSet<>(eventStores);
    }

    public Set<String> getEventSchemas() {
        return new LinkedHashSet<>(eventSchemas.keySet());
    }

    public Map<String, String> getSchemaVersions() {
        return new HashMap<>(eventSchemas);
    }

    public Set<String> getEventSources() {
        return new LinkedHashSet<>(eventSources);
    }

    public Set<String> getEventConsumers() {
        return new LinkedHashSet<>(eventConsumers);
    }

    public Set<String> getIdempotencyPatterns() {
        return new LinkedHashSet<>(idempotencyPatterns);
    }

    public Set<String> getErrorHandlingStrategies() {
        return new LinkedHashSet<>(errorHandlingStrategies);
    }

    public Set<String> getProjections() {
        return new LinkedHashSet<>(projections);
    }

    public Set<String> getSagas() {
        return new LinkedHashSet<>(sagas);
    }

    // Mutators
    public void addMessageBroker(String broker, Map<String, Object> config) {
        if (!this.messageBrokers.contains(broker)) {
            this.messageBrokers.add(broker);
            if (config != null) {
                this.brokerConfigurations.put(broker, new HashMap<>(config));
            }
            this.lastUpdated = Instant.now();
        }
    }

    public void addDeadLetterQueue(String queueName, String config) {
        this.deadLetterQueues.add(queueName);
        this.dlqConfigurations.put(queueName, config);
        this.lastUpdated = Instant.now();
    }

    public void addEventHandler(String handler, String idempotencyPattern) {
        this.eventHandlers.add(handler);
        if (idempotencyPattern != null) {
            this.idempotencyPatterns.add(idempotencyPattern);
        }
        this.lastUpdated = Instant.now();
    }

    public void addEventStore(String store) {
        this.eventStores.add(store);
        this.lastUpdated = Instant.now();
    }

    public void addEventSchema(String schemaName, String version) {
        this.eventSchemas.put(schemaName, version);
        this.lastUpdated = Instant.now();
    }

    public void addSaga(String saga) {
        this.sagas.add(saga);
        this.lastUpdated = Instant.now();
    }

    public void updateSchemaVersion(String schemaName, String newVersion) {
        if (this.eventSchemas.containsKey(schemaName)) {
            this.eventSchemas.put(schemaName, newVersion);
            this.lastUpdated = Instant.now();
        }
    }

    public void addEventSource(String source) {
        this.eventSources.add(source);
        this.lastUpdated = Instant.now();
    }

    public void addEventConsumer(String consumer) {
        this.eventConsumers.add(consumer);
        this.lastUpdated = Instant.now();
    }

    public void addErrorHandlingStrategy(String strategy) {
        this.errorHandlingStrategies.add(strategy);
        this.lastUpdated = Instant.now();
    }

    public void addProjection(String projection) {
        this.projections.add(projection);
        this.lastUpdated = Instant.now();
    }

    // Validation methods
    public boolean isValid() {
        return !messageBrokers.isEmpty() || !eventSchemas.isEmpty();
    }

    public boolean hasEventSourcing() {
        return !eventStores.isEmpty();
    }

    public boolean hasResiliencePatterns() {
        return !idempotencyPatterns.isEmpty() || !errorHandlingStrategies.isEmpty();
    }

    @Override
    public String toString() {
        return "EventDrivenContext{" +
                "contextId='" + contextId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", createdAt=" + createdAt +
                ", lastUpdated=" + lastUpdated +
                ", brokers=" + messageBrokers.size() +
                ", schemas=" + eventSchemas.size() +
                ", handlers=" + eventHandlers.size() +
                ", eventStores=" + eventStores.size() +
                ", sagas=" + sagas.size() +
                ", projections=" + projections.size() +
                '}';
    }
}
