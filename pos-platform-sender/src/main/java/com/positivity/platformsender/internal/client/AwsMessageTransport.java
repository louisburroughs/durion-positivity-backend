package com.positivity.platformsender.internal.client;

import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.service.MessageTransport;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 * <p>Provider answers are classified by status, not by exception type: throttling and a 5xx are
 * transient; every other 4xx (a rejected message, an opted-out number, an unverified identity) is
 * permanent for this message. A failure with no answer is transient only when it happened before the
 * request left; otherwise it is uncertain ({@link #classifyClientFailure}). Retrying is the caller's
 * job: the clients carry no SDK retries, which could repeat a request that was already delivered.
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
            return classifyClientFailure(e);
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

    /**
     * A failure with no provider answer. When it is known to have happened before the request left
     * (no connection, an unknown host, no credentials) nothing was sent and a retry is safe;
     * anything else (a read timeout, a connection lost mid-exchange) may have been delivered, and
     * is {@link TransportResult.Kind#UNCERTAIN}. The SES and SMS clients run without SDK retries
     * ({@code AwsClientConfig}) for the same reason.
     */
    static TransportResult classifyClientFailure(SdkException e) {
        if (failedBeforeSending(e)) {
            return TransportResult.transientFailure("PROVIDER_UNREACHABLE", describe(e));
        }
        return TransportResult.uncertain("PROVIDER_NO_RESPONSE", describe(e));
    }

    private static boolean failedBeforeSending(Throwable failure) {
        String message = failure.getMessage();
        if (message != null && message.contains("Unable to load credentials")) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
            if (cause instanceof SocketTimeoutException timeout
                    && timeout.getMessage() != null
                    && timeout.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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
