package com.pos.agent.context;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
        if (broker != null && this.messageBrokers.add(broker)) {
            if (config != null) {
                this.brokerConfigurations.put(broker, new HashMap<>(config));
            }
            updateTimestamp();
        }
    }

    public void addDeadLetterQueue(String queueName, String config) {
        if (queueName != null) {
            this.deadLetterQueues.add(queueName);
            this.dlqConfigurations.put(queueName, config);
            updateTimestamp();
        }
    }

    public void addEventHandler(String handler, String idempotencyPattern) {
        if (handler != null) {
            this.eventHandlers.add(handler);
            if (idempotencyPattern != null) {
                this.idempotencyPatterns.add(idempotencyPattern);
            }
            updateTimestamp();
        }
    }

    public void addEventStore(String store) {
        if (store != null && this.eventStores.add(store)) {
            updateTimestamp();
        }
    }

    public void addEventSchema(String schemaName, String version) {
        if (schemaName != null && version != null) {
            this.eventSchemas.put(schemaName, version);
            updateTimestamp();
        }
    }

    public void addSaga(String saga) {
        if (saga != null && this.sagas.add(saga)) {
            updateTimestamp();
        }
    }

    public void updateSchemaVersion(String schemaName, String newVersion) {
        if (schemaName != null && newVersion != null && this.eventSchemas.containsKey(schemaName)) {
            this.eventSchemas.put(schemaName, newVersion);
            updateTimestamp();
        }
    }

    public void addEventSource(String source) {
        if (source != null && this.eventSources.add(source)) {
            updateTimestamp();
        }
    }

    public void addEventConsumer(String consumer) {
        if (consumer != null && this.eventConsumers.add(consumer)) {
            updateTimestamp();
        }
    }

    public void addErrorHandlingStrategy(String strategy) {
        if (strategy != null && this.errorHandlingStrategies.add(strategy)) {
            updateTimestamp();
        }
    }

    public void addProjection(String projection) {
        if (projection != null && this.projections.add(projection)) {
            updateTimestamp();
        }
    }

    public void addIdempotencyPattern(String pattern) {
        if (pattern != null && this.idempotencyPatterns.add(pattern)) {
            updateTimestamp();
        }
    }

    public void addHandlerType(String handler, String type) {
        if (handler != null && type != null) {
            this.handlerTypes.put(handler, type);
            updateTimestamp();
        }
    }

    public void addOrchestration(String pattern) {
        if (pattern != null && this.orchestration.add(pattern)) {
            updateTimestamp();
        }
    }

    public void addChoreography(String pattern) {
        if (pattern != null && this.choreography.add(pattern)) {
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
            domain("event-driven");
            contextType("event-driven-context");
        }

        public Builder addMessageBroker(String broker, Map<String, Object> config) {
            if (broker != null) {
                messageBrokers.add(broker);
                if (config != null) {
                    brokerConfigurations.put(broker, new HashMap<>(config));
                }
            }
            return this;
        }

        public Builder messageBrokers(Set<String> brokers) {
            if (brokers != null) {
                messageBrokers.addAll(brokers);
            }
            return this;
        }

        public Builder brokerConfigurations(Map<String, Map<String, Object>> configs) {
            if (configs != null) {
                configs.forEach((k, v) -> brokerConfigurations.put(k, v != null ? new HashMap<>(v) : new HashMap<>()));
            }
            return this;
        }

        public Builder addDeadLetterQueue(String queueName, String config) {
            if (queueName != null) {
                deadLetterQueues.add(queueName);
                dlqConfigurations.put(queueName, config);
            }
            return this;
        }

        public Builder deadLetterQueues(Set<String> queues) {
            if (queues != null) {
                deadLetterQueues.addAll(queues);
            }
            return this;
        }

        public Builder dlqConfigurations(Map<String, String> configs) {
            if (configs != null) {
                dlqConfigurations.putAll(configs);
            }
            return this;
        }

        public Builder addEventHandler(String handler, String idempotencyPattern) {
            if (handler != null) {
                eventHandlers.add(handler);
                if (idempotencyPattern != null) {
                    idempotencyPatterns.add(idempotencyPattern);
                }
            }
            return this;
        }

        public Builder eventHandlers(Set<String> handlers) {
            if (handlers != null) {
                eventHandlers.addAll(handlers);
            }
            return this;
        }

        public Builder handlerTypes(Map<String, String> types) {
            if (types != null) {
                handlerTypes.putAll(types);
            }
            return this;
        }

        public Builder addEventStore(String store) {
            if (store != null) {
                eventStores.add(store);
            }
            return this;
        }

        public Builder eventStores(Set<String> stores) {
            if (stores != null) {
                eventStores.addAll(stores);
            }
            return this;
        }

        public Builder addEventSchema(String name, String version) {
            if (name != null && version != null) {
                eventSchemas.put(name, version);
            }
            return this;
        }

        public Builder eventSchemas(Map<String, String> schemas) {
            if (schemas != null) {
                eventSchemas.putAll(schemas);
            }
            return this;
        }

        public Builder addEventSource(String source) {
            if (source != null) {
                eventSources.add(source);
            }
            return this;
        }

        public Builder eventSources(Set<String> sources) {
            if (sources != null) {
                eventSources.addAll(sources);
            }
            return this;
        }

        public Builder addEventConsumer(String consumer) {
            if (consumer != null) {
                eventConsumers.add(consumer);
            }
            return this;
        }

        public Builder eventConsumers(Set<String> consumers) {
            if (consumers != null) {
                eventConsumers.addAll(consumers);
            }
            return this;
        }

        public Builder addErrorHandlingStrategy(String strategy) {
            if (strategy != null) {
                errorHandlingStrategies.add(strategy);
            }
            return this;
        }

        public Builder errorHandlingStrategies(Set<String> strategies) {
            if (strategies != null) {
                errorHandlingStrategies.addAll(strategies);
            }
            return this;
        }

        public Builder addProjections(Set<String> projections) {
            if (projections != null) {
                this.projections.addAll(projections);
            }
            return this;
        }

        public Builder addProjection(String projection) {
            if (projection != null) {
                this.projections.add(projection);
            }
            return this;
        }

        public Builder addSaga(String saga) {
            if (saga != null) {
                sagas.add(saga);
            }
            return this;
        }

        public Builder sagas(Set<String> sagas) {
            if (sagas != null) {
                this.sagas.addAll(sagas);
            }
            return this;
        }

        public Builder choreography(Set<String> choreography) {
            if (choreography != null) {
                this.choreography.addAll(choreography);
            }
            return this;
        }

        public Builder addChoreography(String pattern) {
            if (pattern != null) {
                this.choreography.add(pattern);
            }
            return this;
        }

        public Builder orchestration(Set<String> orchestration) {
            if (orchestration != null) {
                this.orchestration.addAll(orchestration);
            }
            return this;
        }

        public Builder addOrchestration(String pattern) {
            if (pattern != null) {
                this.orchestration.add(pattern);
            }
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
