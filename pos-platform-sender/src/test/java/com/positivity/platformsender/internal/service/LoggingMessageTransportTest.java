package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.service.MessageTransport.TransportResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LoggingMessageTransport — accepts without contacting anyone")
class LoggingMessageTransportTest {

    @Test
    @DisplayName("accepts with a provider id derived from the caller's key")
    void accepts() {
        UUID messageId = UUID.fromString("01990000-0000-7000-8000-0000000000c1");

        TransportResult result = new LoggingMessageTransport()
                .send(new MessageTransport.OutboundMessage(
                        messageId, UUID.randomUUID(), MessageChannel.SMS, "+15550100100", "SPRING24", null, "Hi"));

        assertThat(result).isEqualTo(TransportResult.accepted("log-" + messageId));
    }
}
