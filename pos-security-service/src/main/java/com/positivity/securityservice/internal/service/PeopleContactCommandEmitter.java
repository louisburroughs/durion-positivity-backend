package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemoveRequestedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Queues person/link commands toward pos-people-contact through the transactional outbox
 * (ADR-0044 §2 / amended ADR-0043, #876). Command messages use the plain
 * {@code {commandType, eventId, schemaVersion, payload}} shape; the eventId is the receiver's
 * idempotency key. No-op when the Kafka feature flag is off — callers' flows must tolerate the
 * pending window either way.
 */
@Slf4j
@Component
public class PeopleContactCommandEmitter {

    private final ObjectMapper objectMapper;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String peopleContactCommandsTopic;

    public PeopleContactCommandEmitter(
            ObjectMapper objectMapper,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            @Value("${pos.security-service.kafka.people-contact-commands-topic:people-contact.commands.v1}")
                    String peopleContactCommandsTopic) {
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
        this.peopleContactCommandsTopic = peopleContactCommandsTopic;
    }

    public void requestPersonUpsert(@NonNull PersonUpsertRequestedV1 command) {
        publish(
                PersonUpsertRequestedV1.COMMAND_TYPE,
                PersonUpsertRequestedV1.SCHEMA_VERSION,
                command.personId().toString(),
                command);
    }

    public void requestLinkCreate(@NonNull UserPersonLinkCreateRequestedV1 command) {
        publish(
                UserPersonLinkCreateRequestedV1.COMMAND_TYPE,
                UserPersonLinkCreateRequestedV1.SCHEMA_VERSION,
                command.username(),
                command);
    }

    public void requestLinkRemove(@NonNull UserPersonLinkRemoveRequestedV1 command) {
        publish(
                UserPersonLinkRemoveRequestedV1.COMMAND_TYPE,
                UserPersonLinkRemoveRequestedV1.SCHEMA_VERSION,
                command.username(),
                command);
    }

    private void publish(String commandType, int schemaVersion, String recordKey, Object payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            log.debug("Kafka disabled — dropping {} command for key={}", commandType, recordKey);
            return;
        }
        UUID eventId = UUIDv7Generator.generate();
        String message;
        try {
            message = objectMapper.writeValueAsString(
                    new CommandMessage(commandType, eventId.toString(), schemaVersion, payload));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize " + commandType + " command", e);
        }
        writer.publishRaw(peopleContactCommandsTopic, recordKey, message);
        log.debug("Queued {} key={} eventId={}", commandType, recordKey, eventId);
    }

    /** Command message shape consumed by {@code people-contact.commands.v1} (see #874 listener). */
    record CommandMessage(
            @NonNull String commandType,
            @NonNull String eventId,
            int schemaVersion,
            @NonNull Object payload) {}
}
