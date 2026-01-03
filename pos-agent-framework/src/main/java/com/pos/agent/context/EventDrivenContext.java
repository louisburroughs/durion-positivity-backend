package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Context model for event-driven architecture scenarios.
 * Tracks message brokers, event handlers, schemas, and event sourcing patterns.
 */
public class EventDrivenContext extends AgentContext {

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

    private EventDrivenContext(Builder builder) {
        super(builder);
        this.messageBrokers = builder.messageBrokers;
        this.brokerConfigurations = builder.brokerConfigurations;
        this.deadLetterQueues = builder.deadLetterQueues;
        this.dlqConfigurations = builder.dlqConfigurations;

        this.eventHandlers = builder.eventHandlers;
        this.handlerTypes = builder.handlerTypes;
        this.eventStores = builder.eventStores;
        this.eventSchemas = builder.eventSchemas;
        this.eventSources = builder.eventSources;
        this.eventConsumers = builder.eventConsumers;
        this.idempotencyPatterns = builder.idempotencyPatterns;
        this.errorHandlingStrategies = builder.errorHandlingStrategies;
        this.projections = builder.projections;

        this.sagas = builder.sagas;
        this.choreography = builder.choreography;
        this.orchestration = builder.orchestration;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public Set<String> getMessageBrokers() {
        return new LinkedHashSet<>(messageBrokers);
    }

    public Map<String, Map<String, Object>> getBrokerConfigurations() {
        Map<String, Map<String, Object>> result = new HashMap<>();
        brokerConfigurations.forEach((key, value) -> result.put(key, new HashMap<>(value)));
        return result;
    }

    public Set<String> getDeadLetterQueues() {
        return new LinkedHashSet<>(deadLetterQueues);
    }

    public Map<String, String> getDeadLetterQueuesMap() {
        return new HashMap<>(dlqConfigurations);
    }

    public Set<String> getEventHandlers() {
        return new LinkedHashSet<>(eventHandlers);
    }

    public Map<String, String> getHandlerTypes() {
        return new HashMap<>(handlerTypes);
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

    public Set<String> getChoreography() {
        return new LinkedHashSet<>(choreography);
    }

    public Set<String> getOrchestration() {
        return new LinkedHashSet<>(orchestration);
    }

    // Mutators
    public void addMessageBroker(String broker, Map<String, Object> config) {
        Objects.requireNonNull(broker, "Message broker cannot be null");
        Objects.requireNonNull(config, "Broker configuration cannot be null");
        if (this.messageBrokers.add(broker)) {
            this.brokerConfigurations.put(broker, new HashMap<>(config));
            updateTimestamp();
        }
    }

    public void addDeadLetterQueue(String queueName, String config) {
        Objects.requireNonNull(queueName, "Queue name cannot be null");
        Objects.requireNonNull(config, "DLQ configuration cannot be null");
        this.deadLetterQueues.add(queueName);
        this.dlqConfigurations.put(queueName, config);
        updateTimestamp();
    }

    public void addEventHandler(String handler, String idempotencyPattern) {
        Objects.requireNonNull(handler, "Event handler cannot be null");
        Objects.requireNonNull(idempotencyPattern, "Idempotency pattern cannot be null");
        this.eventHandlers.add(handler);
        this.idempotencyPatterns.add(idempotencyPattern);
        updateTimestamp();
    }

    public void addEventStore(String store) {
        Objects.requireNonNull(store, "Event store cannot be null");
        if (this.eventStores.add(store)) {
            updateTimestamp();
        }
    }

    public void addEventSchema(String schemaName, String version) {
        Objects.requireNonNull(schemaName, "Schema name cannot be null");
        Objects.requireNonNull(version, "Schema version cannot be null");
        this.eventSchemas.put(schemaName, version);
        updateTimestamp();
    }

    public void addSaga(String saga) {
        Objects.requireNonNull(saga, "Saga cannot be null");
        if (this.sagas.add(saga)) {
            updateTimestamp();
        }
    }

    public void updateSchemaVersion(String schemaName, String newVersion) {
        Objects.requireNonNull(schemaName, "Schema name cannot be null");
        Objects.requireNonNull(newVersion, "Schema version cannot be null");
        if (this.eventSchemas.containsKey(schemaName)) {
            this.eventSchemas.put(schemaName, newVersion);
            updateTimestamp();
        }
    }

    public void addEventSource(String source) {
        Objects.requireNonNull(source, "Event source cannot be null");
        if (this.eventSources.add(source)) {
            updateTimestamp();
        }
    }

    public void addEventConsumer(String consumer) {
        Objects.requireNonNull(consumer, "Event consumer cannot be null");
        if (this.eventConsumers.add(consumer)) {
            updateTimestamp();
        }
    }

    public void addErrorHandlingStrategy(String strategy) {
        Objects.requireNonNull(strategy, "Error handling strategy cannot be null");
        if (this.errorHandlingStrategies.add(strategy)) {
            updateTimestamp();
        }
    }

    public void addProjection(String projection) {
        Objects.requireNonNull(projection, "Projection cannot be null");
        if (this.projections.add(projection)) {
            updateTimestamp();
        }
    }

    public void addIdempotencyPattern(String pattern) {
        Objects.requireNonNull(pattern, "Idempotency pattern cannot be null");
        if (this.idempotencyPatterns.add(pattern)) {
            updateTimestamp();
        }
    }

    public void addHandlerType(String handler, String type) {
        Objects.requireNonNull(handler, "Handler cannot be null");
        Objects.requireNonNull(type, "Handler type cannot be null");
        this.handlerTypes.put(handler, type);
        updateTimestamp();
    }

    public void addOrchestration(String pattern) {
        Objects.requireNonNull(pattern, "Orchestration pattern cannot be null");
        if (this.orchestration.add(pattern)) {
            updateTimestamp();
        }
    }

    public void addChoreography(String pattern) {
        Objects.requireNonNull(pattern, "Choreography pattern cannot be null");
        if (this.choreography.add(pattern)) {
            updateTimestamp();
        }
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
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", createdAt=" + getCreatedAt() +
                ", lastUpdated=" + getLastUpdated() +
                ", brokers=" + messageBrokers.size() +
                ", schemas=" + eventSchemas.size() +
                ", handlers=" + eventHandlers.size() +
                ", eventStores=" + eventStores.size() +
                ", sagas=" + sagas.size() +
                ", projections=" + projections.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> messageBrokers = new LinkedHashSet<>();
        private Map<String, Map<String, Object>> brokerConfigurations = new HashMap<>();
        private Set<String> deadLetterQueues = new LinkedHashSet<>();
        private Map<String, String> dlqConfigurations = new HashMap<>();

        private Set<String> eventHandlers = new LinkedHashSet<>();
        private Map<String, String> handlerTypes = new HashMap<>();
        private Set<String> eventStores = new LinkedHashSet<>();
        private Map<String, String> eventSchemas = new HashMap<>();
        private Set<String> eventSources = new LinkedHashSet<>();
        private Set<String> eventConsumers = new LinkedHashSet<>();
        private Set<String> idempotencyPatterns = new LinkedHashSet<>();
        private Set<String> errorHandlingStrategies = new LinkedHashSet<>();
        private Set<String> projections = new LinkedHashSet<>();

        private Set<String> sagas = new LinkedHashSet<>();
        private Set<String> choreography = new LinkedHashSet<>();
        private Set<String> orchestration = new LinkedHashSet<>();

        public Builder() {
            agentDomain("event-driven");
            contextType("event-driven-context");
        }

        public Builder addMessageBroker(String broker, Map<String, Object> config) {
            Objects.requireNonNull(broker, "Message broker cannot be null");
            Objects.requireNonNull(config, "Broker configuration cannot be null");
            messageBrokers.add(broker);
            brokerConfigurations.put(broker, new HashMap<>(config));
            return this;
        }

        public Builder messageBrokers(Set<String> brokers) {
            Objects.requireNonNull(brokers, "Message brokers cannot be null");
            messageBrokers.addAll(brokers);
            return this;
        }

        public Builder brokerConfigurations(Map<String, Map<String, Object>> configs) {
            Objects.requireNonNull(configs, "Broker configurations cannot be null");
            configs.forEach((k, v) -> brokerConfigurations.put(k, v != null ? new HashMap<>(v) : new HashMap<>()));
            return this;
        }

        public Builder addDeadLetterQueue(String queueName, String config) {
            Objects.requireNonNull(queueName, "Queue name cannot be null");
            Objects.requireNonNull(config, "DLQ configuration cannot be null");
            deadLetterQueues.add(queueName);
            dlqConfigurations.put(queueName, config);
            return this;
        }

        public Builder deadLetterQueues(Set<String> queues) {
            Objects.requireNonNull(queues, "Dead letter queues cannot be null");
            deadLetterQueues.addAll(queues);
            return this;
        }

        public Builder dlqConfigurations(Map<String, String> configs) {
            Objects.requireNonNull(configs, "DLQ configurations cannot be null");
            dlqConfigurations.putAll(configs);
            return this;
        }

        public Builder addEventHandler(String handler, String idempotencyPattern) {
            Objects.requireNonNull(handler, "Event handler cannot be null");
            Objects.requireNonNull(idempotencyPattern, "Idempotency pattern cannot be null");
            eventHandlers.add(handler);
            idempotencyPatterns.add(idempotencyPattern);
            return this;
        }

        public Builder eventHandlers(Set<String> handlers) {
            Objects.requireNonNull(handlers, "Event handlers cannot be null");
            eventHandlers.addAll(handlers);
            return this;
        }

        public Builder handlerTypes(Map<String, String> types) {
            Objects.requireNonNull(types, "Handler types cannot be null");
            handlerTypes.putAll(types);
            return this;
        }

        public Builder addHandlerType(String handler, String event) {
            Objects.requireNonNull(handler, "Handler cannot be null");
            Objects.requireNonNull(event, "Handler type cannot be null");
            handlerTypes.put(handler, event);
            return this;
        }

        public Builder addEventStore(String store) {
            Objects.requireNonNull(store, "Event store cannot be null");
            eventStores.add(store);
            return this;
        }

        public Builder eventStores(Set<String> stores) {
            Objects.requireNonNull(stores, "Event stores cannot be null");
            eventStores.addAll(stores);
            return this;
        }

        public Builder addEventSchema(String name, String version) {
            Objects.requireNonNull(name, "Schema name cannot be null");
            Objects.requireNonNull(version, "Schema version cannot be null");
            eventSchemas.put(name, version);
            return this;
        }

        public Builder eventSchemas(Map<String, String> schemas) {
            Objects.requireNonNull(schemas, "Event schemas cannot be null");
            eventSchemas.putAll(schemas);
            return this;
        }

        public Builder addEventSource(String source) {
            Objects.requireNonNull(source, "Event source cannot be null");
            eventSources.add(source);
            return this;
        }

        public Builder eventSources(Set<String> sources) {
            Objects.requireNonNull(sources, "Event sources cannot be null");
            eventSources.addAll(sources);
            return this;
        }

        public Builder addEventConsumer(String consumer) {
            Objects.requireNonNull(consumer, "Event consumer cannot be null");
            eventConsumers.add(consumer);
            return this;
        }

        public Builder eventConsumers(Set<String> consumers) {
            Objects.requireNonNull(consumers, "Event consumers cannot be null");
            eventConsumers.addAll(consumers);
            return this;
        }

        public Builder addErrorHandlingStrategy(String strategy) {
            Objects.requireNonNull(strategy, "Error handling strategy cannot be null");
            errorHandlingStrategies.add(strategy);
            return this;
        }

        public Builder errorHandlingStrategies(Set<String> strategies) {
            Objects.requireNonNull(strategies, "Error handling strategies cannot be null");
            errorHandlingStrategies.addAll(strategies);
            return this;
        }

        public Builder addProjections(Set<String> projections) {
            Objects.requireNonNull(projections, "Projections cannot be null");
            this.projections.addAll(projections);
            return this;
        }

        public Builder addProjection(String projection) {
            Objects.requireNonNull(projection, "Projection cannot be null");
            this.projections.add(projection);
            return this;
        }

        public Builder addSaga(String saga) {
            Objects.requireNonNull(saga, "Saga cannot be null");
            sagas.add(saga);
            return this;
        }

        public Builder sagas(Set<String> sagas) {
            Objects.requireNonNull(sagas, "Sagas cannot be null");
            this.sagas.addAll(sagas);
            return this;
        }

        public Builder choreography(Set<String> choreography) {
            Objects.requireNonNull(choreography, "Choreography cannot be null");
            this.choreography.addAll(choreography);
            return this;
        }

        public Builder addChoreography(String pattern) {
            Objects.requireNonNull(pattern, "Choreography pattern cannot be null");
            this.choreography.add(pattern);
            return this;
        }

        public Builder orchestration(Set<String> orchestration) {
            Objects.requireNonNull(orchestration, "Orchestration cannot be null");
            this.orchestration.addAll(orchestration);
            return this;
        }

        public Builder addOrchestration(String pattern) {
            Objects.requireNonNull(pattern, "Orchestration pattern cannot be null");
            this.orchestration.add(pattern);
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public EventDrivenContext build() {
            return new EventDrivenContext(this);
        }
    }
}
