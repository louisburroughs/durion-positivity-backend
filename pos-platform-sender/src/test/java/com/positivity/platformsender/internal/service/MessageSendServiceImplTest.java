package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.platformsender.internal.dto.SendMessageRequest;
import com.positivity.platformsender.internal.entity.SentMessage;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.enums.SentMessageStatus;
import com.positivity.platformsender.internal.exception.MessageRefusedException;
import com.positivity.platformsender.internal.exception.SenderUnavailableException;
import com.positivity.platformsender.internal.repository.SentMessageRepository;
import com.positivity.platformsender.internal.service.MessageTransport.OutboundMessage;
import com.positivity.platformsender.internal.service.MessageTransport.TransportResult;
import com.positivity.platformsender.internal.service.RecipientAddressResolver.Resolution;
import com.positivity.platformsender.internal.service.RecipientAddressResolver.Unresolved;
import com.positivity.tenancy.TenantResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link MessageSendServiceImpl}: FI-2 §1's status classes and its "a replayed messageId MUST NOT
 * produce a second delivery" rule. The claim is a committed {@code PENDING} row, the provider call
 * runs outside any transaction, and the outcome settles the row (accepted / refused) or releases it
 * (transient), so a replay always answers exactly as the first request was answered.
 */
@DisplayName("MessageSendServiceImpl — idempotent send")
class MessageSendServiceImplTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    private static final UUID PARTY = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final UUID CLAIM_ID = UUID.fromString("01990000-0000-7000-8000-0000000000d1");

    private final SentMessageRepository repository = mock(SentMessageRepository.class);
    private final RecipientAddressResolver resolver = mock(RecipientAddressResolver.class);
    private final MessageTransport transport = mock(MessageTransport.class);
    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private MessageSendServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MessageSendServiceImpl(
                repository, resolver, transport, tenantResolver, mock(PlatformTransactionManager.class));
        when(tenantResolver.require()).thenReturn(TENANT);
        when(repository.findByMessageId(MESSAGE_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            SentMessage claim = invocation.getArgument(0);
            claim.setSentMessageId(CLAIM_ID);
            return claim;
        });
        when(repository.findById(CLAIM_ID))
                .thenAnswer(_ -> Optional.of(SentMessage.builder()
                        .sentMessageId(CLAIM_ID)
                        .messageId(MESSAGE_ID)
                        .status(SentMessageStatus.PENDING)
                        .build()));
        when(resolver.resolve(MessageChannel.EMAIL, PARTY, null)).thenReturn(new Resolution("ada@example.com", null));
    }

    private static SendMessageRequest email() {
        return new SendMessageRequest(
                MESSAGE_ID, MessageChannel.EMAIL, PARTY, null, "SPRING24", "Spring tyres", "Time for new tyres");
    }

    private SentMessage settled() {
        ArgumentCaptor<SentMessage> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("first request")
    class FirstRequest {

        @Test
        @DisplayName("an accepted message settles ACCEPTED and answers 202 with the provider id and address hash")
        void accepted() {
            when(transport.send(any())).thenReturn(TransportResult.accepted("ses-1"));

            MessageSendService.SendResult result = service.send(email());

            assertThat(result.replay()).isFalse();
            assertThat(result.response().providerMessageId()).isEqualTo("ses-1");
            assertThat(result.response().addressHash()).isEqualTo(AddressNormalizer.hash("ada@example.com"));
            SentMessage row = settled();
            assertThat(row.getStatus()).isEqualTo(SentMessageStatus.ACCEPTED);
            assertThat(row.getProviderMessageId()).isEqualTo("ses-1");
        }

        @Test
        @DisplayName("the transport gets the resolved address, the bound tenant and the caller's key")
        void transportGetsResolvedAddressAndTenant() {
            when(transport.send(any())).thenReturn(TransportResult.accepted("ses-1"));

            service.send(email());

            ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.captor();
            verify(transport).send(sent.capture());
            assertThat(sent.getValue().address()).isEqualTo("ada@example.com");
            assertThat(sent.getValue().tenantId()).isEqualTo(TENANT);
            assertThat(sent.getValue().messageId()).isEqualTo(MESSAGE_ID);
        }

        @Test
        @DisplayName("the claim is committed PENDING before the provider is called")
        void claimsBeforeSending() {
            when(transport.send(any())).thenAnswer(_ -> {
                ArgumentCaptor<SentMessage> claimed = ArgumentCaptor.captor();
                verify(repository).saveAndFlush(claimed.capture());
                assertThat(claimed.getValue().getStatus()).isEqualTo(SentMessageStatus.PENDING);
                return TransportResult.accepted("ses-1");
            });

            service.send(email());
        }

        @Test
        @DisplayName("an unresolvable recipient settles REJECTED and is a permanent refusal; nothing is sent")
        void unresolvedIsRefused() {
            when(resolver.resolve(MessageChannel.EMAIL, PARTY, null))
                    .thenReturn(new Resolution(null, Unresolved.NO_CONTACT_POINT));

            assertThatExceptionOfType(MessageRefusedException.class)
                    .isThrownBy(() -> service.send(email()))
                    .satisfies(e -> assertThat(e.getCode()).isEqualTo("NO_CONTACT_POINT"));
            assertThat(settled().getStatus()).isEqualTo(SentMessageStatus.REJECTED);
            verify(transport, never()).send(any());
        }

        @Test
        @DisplayName("a provider refusal settles REJECTED and is a permanent refusal carrying the provider code")
        void providerRefusal() {
            when(transport.send(any()))
                    .thenReturn(TransportResult.permanentFailure("MessageRejected", "Email address is not verified."));

            assertThatExceptionOfType(MessageRefusedException.class)
                    .isThrownBy(() -> service.send(email()))
                    .satisfies(e -> assertThat(e.getCode()).isEqualTo("MessageRejected"));
            SentMessage row = settled();
            assertThat(row.getStatus()).isEqualTo(SentMessageStatus.REJECTED);
            assertThat(row.getFailureReason()).isEqualTo("Email address is not verified.");
        }

        @Test
        @DisplayName("a transient failure releases the claim so the caller's retry can send")
        void transientReleasesClaim() {
            when(transport.send(any())).thenReturn(TransportResult.transientFailure("Throttling", "Rate exceeded"));

            assertThatExceptionOfType(SenderUnavailableException.class)
                    .isThrownBy(() -> service.send(email()))
                    .satisfies(e -> assertThat(e.getCode()).isEqualTo("Throttling"));
            verify(repository).deleteById(CLAIM_ID);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an unexpected transport fault releases the claim and propagates")
        void unexpectedFaultReleasesClaim() {
            when(transport.send(any())).thenThrow(new IllegalStateException("bug"));

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> service.send(email()));
            verify(repository).deleteById(CLAIM_ID);
        }

        @Test
        @DisplayName("a failed address lookup releases the claim and propagates; nothing is sent")
        void resolverFaultReleasesClaim() {
            when(resolver.resolve(MessageChannel.EMAIL, PARTY, null))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection reset"));

            assertThatExceptionOfType(org.springframework.dao.DataAccessResourceFailureException.class)
                    .isThrownBy(() -> service.send(email()));
            verify(repository).deleteById(CLAIM_ID);
            verify(transport, never()).send(any());
        }

        @Test
        @DisplayName("an EMAIL without a subject is refused before anything is claimed")
        void emailNeedsSubject() {
            SendMessageRequest noSubject =
                    new SendMessageRequest(MESSAGE_ID, MessageChannel.EMAIL, PARTY, null, "SPRING24", " ", "Body");

            assertThatExceptionOfType(MessageRefusedException.class)
                    .isThrownBy(() -> service.send(noSubject))
                    .satisfies(e -> assertThat(e.getCode()).isEqualTo(MessageSendServiceImpl.CODE_SUBJECT_REQUIRED));
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an SMS needs no subject")
        void smsNeedsNoSubject() {
            SendMessageRequest sms =
                    new SendMessageRequest(MESSAGE_ID, MessageChannel.SMS, PARTY, PARTY, "SPRING24", null, "Hi");
            when(resolver.resolve(MessageChannel.SMS, PARTY, PARTY)).thenReturn(new Resolution("+15550100100", null));
            when(transport.send(any())).thenReturn(TransportResult.accepted("sms-1"));

            assertThat(service.send(sms).response().providerMessageId()).isEqualTo("sms-1");
        }
    }

    @Nested
    @DisplayName("replay of the same messageId")
    class Replay {

        private void earlier(SentMessageStatus status) {
            when(repository.findByMessageId(MESSAGE_ID))
                    .thenReturn(Optional.of(SentMessage.builder()
                            .messageId(MESSAGE_ID)
                            .status(status)
                            .providerMessageId(status == SentMessageStatus.ACCEPTED ? "ses-1" : null)
                            .addressHash("hash")
                            .failureCode(status == SentMessageStatus.REJECTED ? "NO_CONTACT_POINT" : null)
                            .failureReason(status == SentMessageStatus.REJECTED ? "No email" : null)
                            .build()));
        }

        @Test
        @DisplayName("an accepted message answers 200 with the original ids and is never re-sent")
        void acceptedReplay() {
            earlier(SentMessageStatus.ACCEPTED);

            MessageSendService.SendResult result = service.send(email());

            assertThat(result.replay()).isTrue();
            assertThat(result.response().providerMessageId()).isEqualTo("ses-1");
            assertThat(result.response().addressHash()).isEqualTo("hash");
            verify(transport, never()).send(any());
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a refused message answers the same refusal")
        void rejectedReplay() {
            earlier(SentMessageStatus.REJECTED);

            assertThatExceptionOfType(MessageRefusedException.class)
                    .isThrownBy(() -> service.send(email()))
                    .satisfies(e -> assertThat(e.getCode()).isEqualTo("NO_CONTACT_POINT"));
            verify(transport, never()).send(any());
        }

        @Test
        @DisplayName("an unsettled claim answers transient and is never re-sent")
        void pendingReplay() {
            earlier(SentMessageStatus.PENDING);

            assertThatExceptionOfType(SenderUnavailableException.class)
                    .isThrownBy(() -> service.send(email()))
                    .satisfies(e -> assertThat(e.getCode()).isEqualTo(MessageSendServiceImpl.CODE_IN_FLIGHT));
            verify(transport, never()).send(any());
        }

        @Test
        @DisplayName("losing the claim race to an accepted request answers as its replay")
        void lostRaceToAccepted() {
            when(repository.findByMessageId(MESSAGE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(SentMessage.builder()
                            .messageId(MESSAGE_ID)
                            .status(SentMessageStatus.ACCEPTED)
                            .providerMessageId("ses-1")
                            .build()));
            doThrow(new DataIntegrityViolationException("uq_sent_message"))
                    .when(repository)
                    .saveAndFlush(any());

            assertThat(service.send(email()).replay()).isTrue();
            verify(transport, never()).send(any());
        }

        @Test
        @DisplayName("losing the claim race to a request still in flight answers transient")
        void lostRaceToInFlight() {
            when(repository.findByMessageId(MESSAGE_ID)).thenReturn(Optional.empty());
            doThrow(new DataIntegrityViolationException("uq_sent_message"))
                    .when(repository)
                    .saveAndFlush(any());

            assertThatExceptionOfType(SenderUnavailableException.class).isThrownBy(() -> service.send(email()));
            verify(transport, never()).send(any());
        }
    }
}
