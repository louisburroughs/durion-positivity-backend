package com.positivity.platformsender.internal.client;

import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.service.MessageTransport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * {@code transport: aws}: email through Amazon SES (v2 {@code SendEmail}), SMS through AWS End User
 * Messaging ({@code SendTextMessage}).
 *
 * <p>Every message is tagged with its tenant, its {@code messageId} and its campaign: SES carries
 * the tags into every event it publishes for the message ({@code mail.tags}), End User Messaging
 * carries the {@code Context} map into every SMS event ({@code context}). That is how
 * {@code OutcomeQueuePoller} relays an outcome under the right tenant without a cross-tenant lookup.
 * The configuration set named for each channel is what publishes those events to the outcomes
 * queue.
 *
 * <p>Provider answers are classified by status, not by exception type: throttling, a 5xx and any
 * client-side failure (I/O, timeout) are transient; every other 4xx (a rejected message, an
 * opted-out number, an unverified identity) is permanent for this message. The SDK has already
 * retried throttles and 5xx with its standard backoff before an answer gets here.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.platform-sender", name = "transport", havingValue = "aws")
public class AwsMessageTransport implements MessageTransport {

    private static final int MAX_TAG_VALUE_LENGTH = 256;

    private final SesV2Client ses;
    private final PinpointSmsVoiceV2Client sms;
    private final SenderProperties properties;

    public AwsMessageTransport(SesV2Client ses, PinpointSmsVoiceV2Client sms, SenderProperties properties) {
        this.ses = ses;
        this.sms = sms;
        this.properties = properties;
        if (isBlank(properties.email().fromAddress())) {
            log.warn("pos.platform-sender.email.from-address is not set: every EMAIL send will be refused");
        }
        if (isBlank(properties.email().configurationSet())
                || isBlank(properties.sms().configurationSet())) {
            log.warn("An SES or SMS configuration set is not configured: that channel's outcomes will never arrive");
        }
    }

    @Override
    public @NonNull TransportResult send(@NonNull OutboundMessage message) {
        try {
            return message.channel() == MessageChannel.EMAIL ? sendEmail(message) : sendText(message);
        } catch (AwsServiceException e) {
            return classify(e);
        } catch (SdkException e) {
            // Client side: I/O, timeout, credentials not yet available. Nothing reached the provider
            // that it acknowledged, and a later attempt may succeed.
            return TransportResult.transientFailure("PROVIDER_UNREACHABLE", describe(e));
        }
    }

    private TransportResult sendEmail(OutboundMessage message) {
        String from = properties.email().fromAddress();
        if (isBlank(from)) {
            return TransportResult.permanentFailure("EMAIL_SENDER_NOT_CONFIGURED", "No SES from-address is configured");
        }
        String body = message.body();
        Content content = utf8(body);
        Body emailBody = body.stripLeading().startsWith("<")
                ? Body.builder().html(content).build()
                : Body.builder().text(content).build();
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(
                        Destination.builder().toAddresses(message.address()).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(utf8(message.subject() == null ? "" : message.subject()))
                                .body(emailBody)
                                .build())
                        .build())
                .emailTags(
                        tag(TAG_TENANT_ID, message.tenantId().toString()),
                        tag(TAG_MESSAGE_ID, message.messageId().toString()),
                        tag(TAG_CAMPAIGN, message.campaignCode()));
        if (!isBlank(properties.email().configurationSet())) {
            request.configurationSetName(properties.email().configurationSet());
        }
        return TransportResult.accepted(ses.sendEmail(request.build()).messageId());
    }

    private TransportResult sendText(OutboundMessage message) {
        SendTextMessageRequest.Builder request = SendTextMessageRequest.builder()
                .destinationPhoneNumber(message.address())
                .messageBody(message.body())
                .messageType(properties.sms().messageType())
                .context(Map.of(
                        TAG_TENANT_ID, message.tenantId().toString(),
                        TAG_MESSAGE_ID, message.messageId().toString(),
                        TAG_CAMPAIGN, tagValue(message.campaignCode())));
        if (!isBlank(properties.sms().originationIdentity())) {
            request.originationIdentity(properties.sms().originationIdentity());
        }
        if (!isBlank(properties.sms().configurationSet())) {
            request.configurationSetName(properties.sms().configurationSet());
        }
        return TransportResult.accepted(sms.sendTextMessage(request.build()).messageId());
    }

    static TransportResult classify(AwsServiceException e) {
        String code = e.awsErrorDetails() == null || e.awsErrorDetails().errorCode() == null
                ? "PROVIDER_ERROR"
                : e.awsErrorDetails().errorCode();
        String reason = describe(e);
        if (e.isThrottlingException() || e.statusCode() >= 500) {
            return TransportResult.transientFailure(code, reason);
        }
        return TransportResult.permanentFailure(code, reason);
    }

    private static String describe(SdkException e) {
        if (e instanceof AwsServiceException service
                && service.awsErrorDetails() != null
                && service.awsErrorDetails().errorMessage() != null) {
            return service.awsErrorDetails().errorMessage();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static MessageTag tag(String name, String value) {
        return MessageTag.builder().name(name).value(tagValue(value)).build();
    }

    /** Coerces a value into SES's tag alphabet; UUIDs pass through unchanged. */
    static String tagValue(String value) {
        String coerced = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return coerced.length() <= MAX_TAG_VALUE_LENGTH ? coerced : coerced.substring(0, MAX_TAG_VALUE_LENGTH);
    }

    private static Content utf8(String data) {
        return Content.builder()
                .data(data)
                .charset(StandardCharsets.UTF_8.name())
                .build();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
