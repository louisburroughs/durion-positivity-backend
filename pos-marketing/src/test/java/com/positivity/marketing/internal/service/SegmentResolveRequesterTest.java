package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.config.OutboxEventWriter;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SegmentResolveRequester}.
 *
 * <p>
 * This is the outbound half of the segment-resolution round trip, and the reply
 * has to find its way home without this module keeping a correlation table. Two
 * details carry that:
 *
 * <ul>
 * <li><b>The campaign id travels inside the command</b> alongside the request
 * id, so {@code customer.segment.resolved} can be matched back to the campaign
 * that asked.</li>
 * <li><b>The record key is the segment id</b>, so repeated requests for one
 * segment stay ordered on the same partition.</li>
 * </ul>
 *
 * <p>
 * With Kafka disabled the writer bean is absent; the requester reports an empty
 * correlation id rather than throwing, which is what lets dispatch degrade to
 * "queued nothing" instead of failing the caller's request.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SegmentResolveRequester — outbound segment-resolve command")
class SegmentResolveRequesterTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("01960004-0000-7000-8000-0000000000d1");
    private static final UUID SEGMENT_ID = UUID.fromString("01960004-0000-7000-8000-0000000000d2");
    private static final String TOPIC = "customer.commands.v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxProvider;

    @Mock
    private OutboxEventWriter outboxWriter;

    private SegmentResolveRequester requester;

    @BeforeEach
    void setUp() {
        requester = new SegmentResolveRequester(outboxProvider, objectMapper, TOPIC);
    }

    @Test
    @DisplayName("keys the command on the segment and carries the campaign id for the reply")
    void writesCorrelatedCommand() {
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);

        Optional<UUID> requestId = requester.requestResolve(CAMPAIGN_ID, SEGMENT_ID);

        assertThat(requestId).isPresent();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter)
                .publishRaw(
                        org.mockito.ArgumentMatchers.eq(TOPIC),
                        org.mockito.ArgumentMatchers.eq(SEGMENT_ID.toString()),
                        message.capture());

        JsonNode command = objectMapper.readTree(message.getValue());
        assertThat(command.path("commandType").asString()).isEqualTo("customer.segment.resolve-requested");
        assertThat(command.path("eventId").asString())
                .isEqualTo(requestId.orElseThrow().toString());
        JsonNode payload = command.path("payload");
        assertThat(payload.path("requestId").asString())
                .isEqualTo(requestId.orElseThrow().toString());
        assertThat(payload.path("segmentId").asString()).isEqualTo(SEGMENT_ID.toString());
        assertThat(payload.path("campaignId").asString()).isEqualTo(CAMPAIGN_ID.toString());
    }

    @Test
    @DisplayName("issues a fresh request id per call so replies cannot be confused")
    void requestIdIsUniquePerCall() {
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);

        Optional<UUID> first = requester.requestResolve(CAMPAIGN_ID, SEGMENT_ID);
        Optional<UUID> second = requester.requestResolve(CAMPAIGN_ID, SEGMENT_ID);

        assertThat(first).isPresent().isNotEqualTo(second);
    }

    @Test
    @DisplayName("reports no request when Kafka is disabled instead of failing the caller")
    void kafkaDisabledYieldsEmpty() {
        when(outboxProvider.getIfAvailable()).thenReturn(null);

        assertThat(requester.requestResolve(CAMPAIGN_ID, SEGMENT_ID)).isEmpty();

        verify(outboxWriter, never()).publishRaw(anyString(), anyString(), anyString());
        verify(outboxWriter, never()).publish(anyString(), any());
    }
}
