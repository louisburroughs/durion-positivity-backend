package com.positivity.platformsender.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.service.MessageTransport;
import com.positivity.platformsender.internal.service.MessageTransport.OutboundMessage;
import com.positivity.platformsender.internal.service.MessageTransport.TransportResult;
import com.positivity.platformsender.internal.service.MessageTransport.TransportResult.Kind;
import com.positivity.platformsender.internal.service.TestSenderProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.ConflictException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageResponse;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

@DisplayName("AwsMessageTransport — SES and End User Messaging requests and answer classification")
class AwsMessageTransportTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");

    private final SesV2Client ses = mock(SesV2Client.class);
    private final PinpointSmsVoiceV2Client sms = mock(PinpointSmsVoiceV2Client.class);
    private AwsMessageTransport transport;

    @BeforeEach
    void setUp() {
        transport = new AwsMessageTransport(ses, sms, TestSenderProperties.defaults());
    }

    private static OutboundMessage email(String body) {
        return new OutboundMessage(
                MESSAGE_ID, TENANT, MessageChannel.EMAIL, "ada@example.com", "SPRING 24!", "Spring tyres", body);
    }

    private static OutboundMessage text() {
        return new OutboundMessage(
                MESSAGE_ID, TENANT, MessageChannel.SMS, "+15550100100", "SPRING24", null, "Tyres 20% off");
    }

    @Nested
    @DisplayName("email")
    class Email {

        @Test
        @DisplayName("sends through SES with the configuration set and the tenant/message/campaign tags")
        void sendsTaggedEmail() {
            when(ses.sendEmail(any(SendEmailRequest.class)))
                    .thenReturn(SendEmailResponse.builder().messageId("ses-1").build());

            TransportResult result = transport.send(email("Plain text"));

            assertThat(result).isEqualTo(TransportResult.accepted("ses-1"));
            ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.captor();
            verify(ses).sendEmail(sent.capture());
            SendEmailRequest request = sent.getValue();
            assertThat(request.fromEmailAddress()).isEqualTo("Durion <no-reply@durionpos.org>");
            assertThat(request.destination().toAddresses()).containsExactly("ada@example.com");
            assertThat(request.configurationSetName()).isEqualTo("durion-email");
            assertThat(request.content().simple().subject().data()).isEqualTo("Spring tyres");
            assertThat(request.content().simple().body().text().data()).isEqualTo("Plain text");
            assertThat(request.content().simple().body().html()).isNull();
            assertThat(request.emailTags())
                    .extracting(MessageTag::name, MessageTag::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(MessageTransport.TAG_TENANT_ID, TENANT.toString()),
                            org.assertj.core.groups.Tuple.tuple(MessageTransport.TAG_MESSAGE_ID, MESSAGE_ID.toString()),
                            org.assertj.core.groups.Tuple.tuple(MessageTransport.TAG_CAMPAIGN, "SPRING_24_"));
        }

        @Test
        @DisplayName("a body that starts with markup goes out as HTML")
        void htmlBody() {
            when(ses.sendEmail(any(SendEmailRequest.class)))
                    .thenReturn(SendEmailResponse.builder().messageId("ses-1").build());

            transport.send(email("  <p>Hello</p>"));

            ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.captor();
            verify(ses).sendEmail(sent.capture());
            assertThat(sent.getValue().content().simple().body().html().data()).isEqualTo("  <p>Hello</p>");
            assertThat(sent.getValue().content().simple().body().text()).isNull();
        }

        @Test
        @DisplayName("an SES refusal is permanent and carries the SES error code")
        void rejectionIsPermanent() {
            when(ses.sendEmail(any(SendEmailRequest.class)))
                    .thenThrow(MessageRejectedException.builder()
                            .statusCode(400)
                            .awsErrorDetails(AwsErrorDetails.builder()
                                    .errorCode("MessageRejected")
                                    .errorMessage("Email address is not verified.")
                                    .build())
                            .build());

            TransportResult result = transport.send(email("Body"));

            assertThat(result.kind()).isEqualTo(Kind.PERMANENT_FAILURE);
            assertThat(result.code()).isEqualTo("MessageRejected");
            assertThat(result.reason()).isEqualTo("Email address is not verified.");
        }

        @Test
        @DisplayName("SES throttling is transient")
        void throttlingIsTransient() {
            when(ses.sendEmail(any(SendEmailRequest.class)))
                    .thenThrow(TooManyRequestsException.builder()
                            .statusCode(429)
                            .awsErrorDetails(AwsErrorDetails.builder()
                                    .errorCode("TooManyRequestsException")
                                    .build())
                            .build());

            assertThat(transport.send(email("Body")).kind()).isEqualTo(Kind.TRANSIENT_FAILURE);
        }

        @Test
        @DisplayName("no from-address configured is a permanent refusal and nothing reaches SES")
        void noFromAddress() {
            SenderProperties defaults = TestSenderProperties.defaults();
            AwsMessageTransport unconfigured = new AwsMessageTransport(
                    ses,
                    sms,
                    new SenderProperties(
                            defaults.apiSecret(),
                            "aws",
                            defaults.aws(),
                            new SenderProperties.Email(" ", null),
                            defaults.sms(),
                            defaults.outcomes()));

            assertThat(unconfigured.send(email("Body")).code()).isEqualTo("EMAIL_SENDER_NOT_CONFIGURED");
            verify(ses, never()).sendEmail(any(SendEmailRequest.class));
        }
    }

    @Nested
    @DisplayName("SMS")
    class Sms {

        @Test
        @DisplayName("sends through End User Messaging with the configuration set, type and context")
        void sendsText() {
            when(sms.sendTextMessage(any(SendTextMessageRequest.class)))
                    .thenReturn(
                            SendTextMessageResponse.builder().messageId("sms-1").build());

            assertThat(transport.send(text())).isEqualTo(TransportResult.accepted("sms-1"));

            ArgumentCaptor<SendTextMessageRequest> sent = ArgumentCaptor.captor();
            verify(sms).sendTextMessage(sent.capture());
            SendTextMessageRequest request = sent.getValue();
            assertThat(request.destinationPhoneNumber()).isEqualTo("+15550100100");
            assertThat(request.messageBody()).isEqualTo("Tyres 20% off");
            assertThat(request.messageTypeAsString()).isEqualTo("PROMOTIONAL");
            assertThat(request.configurationSetName()).isEqualTo("durion-sms");
            assertThat(request.originationIdentity()).isNull();
            assertThat(request.context())
                    .containsEntry(MessageTransport.TAG_TENANT_ID, TENANT.toString())
                    .containsEntry(MessageTransport.TAG_MESSAGE_ID, MESSAGE_ID.toString())
                    .containsEntry(MessageTransport.TAG_CAMPAIGN, "SPRING24");
        }

        @Test
        @DisplayName("an opted-out number is a permanent refusal")
        void optedOutIsPermanent() {
            when(sms.sendTextMessage(any(SendTextMessageRequest.class)))
                    .thenThrow(ConflictException.builder()
                            .statusCode(409)
                            .awsErrorDetails(AwsErrorDetails.builder()
                                    .errorCode("ConflictException")
                                    .errorMessage("DESTINATION_PHONE_NUMBER_OPTED_OUT")
                                    .build())
                            .build());

            TransportResult result = transport.send(text());

            assertThat(result.kind()).isEqualTo(Kind.PERMANENT_FAILURE);
            assertThat(result.reason()).isEqualTo("DESTINATION_PHONE_NUMBER_OPTED_OUT");
        }

        @Test
        @DisplayName("a refused connection is transient: nothing left, a retry is safe")
        void refusedConnectionIsTransient() {
            when(sms.sendTextMessage(any(SendTextMessageRequest.class)))
                    .thenThrow(SdkClientException.create(
                            "Unable to execute HTTP request", new java.net.ConnectException("Connection refused")));

            TransportResult result = transport.send(text());

            assertThat(result.kind()).isEqualTo(Kind.TRANSIENT_FAILURE);
            assertThat(result.code()).isEqualTo("PROVIDER_UNREACHABLE");
        }

        @Test
        @DisplayName("a read timeout is uncertain: the message may have been delivered")
        void readTimeoutIsUncertain() {
            when(sms.sendTextMessage(any(SendTextMessageRequest.class)))
                    .thenThrow(SdkClientException.create(
                            "Unable to execute HTTP request", new java.net.SocketTimeoutException("Read timed out")));

            TransportResult result = transport.send(text());

            assertThat(result.kind()).isEqualTo(Kind.UNCERTAIN);
            assertThat(result.code()).isEqualTo("PROVIDER_NO_RESPONSE");
        }
    }

    @Test
    @DisplayName("failures known to precede sending are transient; any other unanswered failure is uncertain")
    void clientFailureClassification() {
        assertThat(AwsMessageTransport.classifyClientFailure(SdkClientException.create(
                                "x", new java.net.SocketTimeoutException("Connect timed out")))
                        .kind())
                .isEqualTo(Kind.TRANSIENT_FAILURE);
        assertThat(AwsMessageTransport.classifyClientFailure(
                                SdkClientException.create("x", new java.net.UnknownHostException("email.us-east-1")))
                        .kind())
                .isEqualTo(Kind.TRANSIENT_FAILURE);
        assertThat(AwsMessageTransport.classifyClientFailure(SdkClientException.create(
                                "Unable to load credentials from any of the providers in the chain"))
                        .kind())
                .isEqualTo(Kind.TRANSIENT_FAILURE);
        assertThat(AwsMessageTransport.classifyClientFailure(SdkClientException.create("Connection reset"))
                        .kind())
                .isEqualTo(Kind.UNCERTAIN);
    }

    @Test
    @DisplayName("a provider 5xx is transient, whatever its type")
    void serverErrorIsTransient() {
        AwsServiceException unavailable = AwsServiceException.builder()
                .statusCode(503)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("ServiceUnavailable")
                        .build())
                .build();

        assertThat(AwsMessageTransport.classify(unavailable).kind()).isEqualTo(Kind.TRANSIENT_FAILURE);
    }

    @Test
    @DisplayName("a tag value is coerced into SES's alphabet and length")
    void tagValueIsCoerced() {
        assertThat(AwsMessageTransport.tagValue("01990000-0000-7000-8000-0000000000c1"))
                .isEqualTo("01990000-0000-7000-8000-0000000000c1");
        assertThat(AwsMessageTransport.tagValue("Black Friday/24")).isEqualTo("Black_Friday_24");
        assertThat(AwsMessageTransport.tagValue("x".repeat(300))).hasSize(256);
    }
}
