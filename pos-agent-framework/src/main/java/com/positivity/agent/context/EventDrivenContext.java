package com.positivity.agent.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context model for event-driven architecture scenarios
 * Supports EventDrivenArchitectureAgent operations and guidance
 * 
 * Requirements: REQ-012.1 - Event schema design and versioning guidance
 */
public class EventDrivenContext {

    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;

    // Event architecture components
    private final List<String> messageBrokers;
    private final List<String> eventSchemas;
    private final List<String> eventHandlers;
    private final Map<String, String> schemaVersions;
    private final List<String> eventSources;
    private final List<String> eventConsumers;

    // Event patterns and configurations
    private final Map<String, Object> brokerConfigurations;
    private final List<String> idempotencyPatterns;
    private final List<String> errorHandlingStrategies;
    private final Map<String, String> deadLetterQueues;

    // Event sourcing and CQRS
    private final List<String> eventStores;
    private final List<String> projections;
    private final List<String> sagas;

    public EventDrivenContext(String contextId, String sessionId) {
        this.contextId = contextId;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastUpdated = Instant.now();

        this.messageBrokers = new ArrayList<>();
        this.eventSchemas = new ArrayList<>();
        this.eventHandlers = new ArrayList<>();
        this.schemaVersions = new HashMap<>();
        this.eventSources = new ArrayList<>();
        this.eventConsumers = new ArrayList<>();

        this.brokerConfigurations = new HashMap<>();
        this.idempotencyPatterns = new ArrayList<>();
        this.errorHandlingStrategies = new ArrayList<>();
        this.deadLetterQueues = new HashMap<>();

        this.eventStores = new ArrayList<>();
        this.projections = new ArrayList<>();
        this.sagas = new ArrayList<>();
    }

    // Message broker management
    public void addMessageBroker(String broker, Map<String, Object> configuration) {
        if (!this.messageBrokers.contains(broker)) {
            this.messageBrokers.add(broker);
            this.brokerConfigurations.put(broker, configuration);
            this.lastUpdated = Instant.now();
        }
    }

    // Event schema management
    public void addEventSchema(String schema, String version) {
        if (!this.eventSchemas.contains(schema)) {
            this.eventSchemas.add(schema);
            this.schemaVersions.put(schema, version);
            this.lastUpdated = Instant.now();
        }
    }

    public void updateSchemaVersion(String schema, String newVersion) {
        if (this.eventSchemas.contains(schema)) {
            this.schemaVersions.put(schema, newVersion);
            this.lastUpdated = Instant.now();
        }
    }

    // Event handler management
    public void addEventHandler(String handler, String idempotencyPattern) {
        if (!this.eventHandlers.contains(handler)) {
            this.eventHandlers.add(handler);
            if (idempotencyPattern != null && !this.idempotencyPatterns.contains(idempotencyPattern)) {
                this.idempotencyPatterns.add(idempotencyPattern);
            }
            this.lastUpdated = Instant.now();
        }
    }

    // Event sourcing support
    public void addEventStore(String eventStore) {
        if (!this.eventStores.contains(eventStore)) {
            this.eventStores.add(eventStore);
            this.lastUpdated = Instant.now();
        }
    }

    public void addProjection(String projection) {
        if (!this.projections.contains(projection)) {
            this.projections.add(projection);
            this.lastUpdated = Instant.now();
        }
    }

    public void addSaga(String saga) {
        if (!this.sagas.contains(saga)) {
            this.sagas.add(saga);
            this.lastUpdated = Instant.now();
        }
    }

    // Error handling and resilience
    public void addErrorHandlingStrategy(String strategy) {
        if (!this.errorHandlingStrategies.contains(strategy)) {
            this.errorHandlingStrategies.add(strategy);
            this.lastUpdated = Instant.now();
        }
    }

    public void addDeadLetterQueue(String eventType, String queueName) {
        this.deadLetterQueues.put(eventType, queueName);
        this.lastUpdated = Instant.now();
    }

    // Context validation
    public boolean isValid() {
        return !messageBrokers.isEmpty() || !eventSchemas.isEmpty() || !eventHandlers.isEmpty();
    }

    public boolean hasEventSourcing() {
        return !eventStores.isEmpty() || !projections.isEmpty() || !sagas.isEmpty();
    }

    public boolean hasResiliencePatterns() {
        return !errorHandlingStrategies.isEmpty() || !deadLetterQueues.isEmpty() || !idempotencyPatterns.isEmpty();
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

    public List<String> getMessageBrokers() {
        return new ArrayList<>(messageBrokers);
    }

    public List<String> getEventSchemas() {
        return new ArrayList<>(eventSchemas);
    }

    public List<String> getEventHandlers() {
        return new ArrayList<>(eventHandlers);
    }

    public Map<String, String> getSchemaVersions() {
        return new HashMap<>(schemaVersions);
    }

    public List<String> getEventSources() {
        return new ArrayList<>(eventSources);
    }

    public List<String> getEventConsumers() {
        return new ArrayList<>(eventConsumers);
    }

    public Map<String, Object> getBrokerConfigurations() {
        return new HashMap<>(brokerConfigurations);
    }

    public List<String> getIdempotencyPatterns() {
        return new ArrayList<>(idempotencyPatterns);
    }

    public List<String> getErrorHandlingStrategies() {
        return new ArrayList<>(errorHandlingStrategies);
    }

    public Map<String, String> getDeadLetterQueues() {
        return new HashMap<>(deadLetterQueues);
    }

    public List<String> getEventStores() {
        return new ArrayList<>(eventStores);
    }

    public List<String> getProjections() {
        return new ArrayList<>(projections);
    }

    public List<String> getSagas() {
        return new ArrayList<>(sagas);
    }

    @Override
    public String toString() {
        return String.format("EventDrivenContext{id='%s', session='%s', brokers=%d, schemas=%d, handlers=%d}",
                contextId, sessionId, messageBrokers.size(), eventSchemas.size(), eventHandlers.size());
    }
}