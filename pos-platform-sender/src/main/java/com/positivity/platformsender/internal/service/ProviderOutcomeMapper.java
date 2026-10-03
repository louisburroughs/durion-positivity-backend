package com.positivity.platformsender.internal.service;

import com.positivity.domainevents.sender.SenderMessageOutcomeV1;
import com.positivity.platformsender.internal.enums.MessageChannel;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Translates one provider event, as it arrives on the outcomes queue, into the FI-2 §2 outcome it
 * means. Pure: no I/O, no tenant, no clock.
 *
 * <p>The queue body is an SNS notification wrapping the event ({@code Type: Notification},
 * {@code Message}: the event JSON as a string), or the bare event when the subscription uses raw
 * message delivery. Two event shapes are understood:
 *
 * <ul>
 *   <li><b>SES event publishing</b> (configuration-set event destination): {@code eventType} is
 *       {@code Delivery}, {@code Bounce}, {@code Complaint}, {@code Open}, {@code Click},
 *       {@code Reject} or {@code RenderingFailure}; {@code mail.messageId} is the provider id;
 *       {@code mail.tags} carries the tenant and message id the sender tagged.
 *   <li><b>End User Messaging SMS</b> (configuration-set event destination): {@code eventType} is
 *       {@code TEXT_*}; {@code messageId} is the provider id; {@code context} carries the tenant and
 *       message id; {@code eventTimestamp} is epoch milliseconds.
 * </ul>
 *
 * <p>Mapping: a delivery is {@code delivered}. An SES {@code Permanent} bounce and an SMS
 * {@code TEXT_INVALID} (the number does not exist) are hard bounces ({@code permanent: true}, which
 * feeds CRM suppression); every other bounce-like outcome (a transient or undetermined SES bounce, an
 * SES reject, an unreachable, blocked, filtered or expired SMS) is a soft bounce. A complaint is
 * {@code complained}; opens and clicks are engagement. Interim events (SES {@code Send} and
 * {@code DeliveryDelay}, SMS {@code TEXT_QUEUED}/{@code TEXT_PENDING}/{@code TEXT_SENT}) mean
 * nothing yet and are ignored.
 *
 * <p>Every bounce and complaint carries the recipient's address (FI-2 §2: the CRM suppression
 * hand-off needs it): the one the event names, else the message's destination. An event that names
 * neither is {@link Unusable}, never relayed without it.
 */
@Component
@RequiredArgsConstructor
public class ProviderOutcomeMapper {

    /** SMS outcomes the number itself causes: the address is bad, so the bounce is hard. */
    private static final Set<String> SMS_HARD_BOUNCES = Set.of("TEXT_INVALID");

    /** SMS outcomes that end delivery without saying the number is bad. */
    private static final Set<String> SMS_SOFT_BOUNCES = Set.of(
            "TEXT_INVALID_MESSAGE",
            "TEXT_UNREACHABLE",
            "TEXT_CARRIER_UNREACHABLE",
            "TEXT_BLOCKED",
            "TEXT_CARRIER_BLOCKED",
            "TEXT_SPAM",
            "TEXT_UNKNOWN",
            "TEXT_TTL_EXPIRED");

    private final ObjectMapper objectMapper;

    /** What to do with one queue message. */
    public sealed interface Mapping permits Mapped, Ignored, Unusable {}

    /**
     * An outcome to relay.
     *
     * @param dedupeKey stable across redeliveries of this provider event (the SNS message id, else
     *     {@code null} for the caller to fall back on the queue's own message id)
     * @param tenantId the tenant the sender tagged the message with
     * @param eventType one of the {@link SenderMessageOutcomeV1} event types
     */
    public record Mapped(
            @Nullable String dedupeKey,
            @NonNull UUID tenantId,
            @NonNull String eventType,
            @NonNull SenderMessageOutcomeV1 outcome)
            implements Mapping {}

    /** A well-formed event that means nothing to FI-2 (interim status, an untracked type). */
    public record Ignored(@NonNull String reason) implements Mapping {}

    /** An event that cannot be relayed: unparsable, or not one this sender tagged. */
    public record Unusable(@NonNull String reason) implements Mapping {}

    public @NonNull Mapping map(@NonNull String queueBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(queueBody);
        } catch (Exception e) {
            return new Unusable("Queue message is not JSON");
        }
        String snsMessageId = null;
        JsonNode event = root;
        if ("Notification".equals(text(root, "Type")) && root.path("Message").isString()) {
            snsMessageId = text(root, "MessageId");
            try {
                event = objectMapper.readTree(root.path("Message").stringValue());
            } catch (Exception e) {
                return new Unusable("SNS notification carries no JSON event");
            }
        }
        if (event.has("mail")) {
            return mapEmail(snsMessageId, event);
        }
        String eventType = text(event, "eventType");
        if (eventType != null && eventType.startsWith("TEXT_")) {
            return mapSms(snsMessageId, event, eventType);
        }
        return new Unusable("Neither an SES nor an SMS event");
    }

    private Mapping mapEmail(@Nullable String dedupeKey, JsonNode event) {
        String eventType = text(event, "eventType");
        if (eventType == null) {
            // Identity notifications (notificationType) carry no tags: only configuration-set event
            // publishing is supported.
            return new Unusable("SES record without eventType (not configuration-set event publishing)");
        }
        JsonNode mail = event.path("mail");
        String providerMessageId = text(mail, "messageId");
        Optional<UUID> tenantId = uuid(firstTag(mail, MessageTransport.TAG_TENANT_ID));
        Optional<UUID> messageId = uuid(firstTag(mail, MessageTransport.TAG_MESSAGE_ID));
        if (providerMessageId == null || tenantId.isEmpty() || messageId.isEmpty()) {
            return new Unusable("SES " + eventType + " event without a messageId or the sender's tags");
        }
        Instant mailTime = instant(text(mail, "timestamp"));
        return switch (eventType) {
            case "Delivery" ->
                mapped(
                        dedupeKey,
                        tenantId.get(),
                        SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED,
                        messageId.get(),
                        MessageChannel.EMAIL,
                        providerMessageId,
                        at(event.path("delivery"), mailTime),
                        null,
                        null,
                        null);
            case "Bounce" -> {
                JsonNode bounce = event.path("bounce");
                String bounceType = text(bounce, "bounceType");
                JsonNode recipient = bounce.path("bouncedRecipients").path(0);
                String reason = join(bounceType, text(bounce, "bounceSubType"), text(recipient, "diagnosticCode"));
                yield mapped(
                        dedupeKey,
                        tenantId.get(),
                        SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED,
                        messageId.get(),
                        MessageChannel.EMAIL,
                        providerMessageId,
                        at(bounce, mailTime),
                        reason,
                        "Permanent".equals(bounceType),
                        recipientAddress(recipient, mail));
            }
            case "Complaint" -> {
                JsonNode complaint = event.path("complaint");
                String feedback = text(complaint, "complaintFeedbackType");
                yield mapped(
                        dedupeKey,
                        tenantId.get(),
                        SenderMessageOutcomeV1.EVENT_TYPE_COMPLAINED,
                        messageId.get(),
                        MessageChannel.EMAIL,
                        providerMessageId,
                        at(complaint, mailTime),
                        feedback == null ? "complaint" : feedback,
                        null,
                        recipientAddress(complaint.path("complainedRecipients").path(0), mail));
            }
            case "Open" ->
                mapped(
                        dedupeKey,
                        tenantId.get(),
                        SenderMessageOutcomeV1.EVENT_TYPE_OPENED,
                        messageId.get(),
                        MessageChannel.EMAIL,
                        providerMessageId,
                        at(event.path("open"), mailTime),
                        null,
                        null,
                        null);
            case "Click" ->
                mapped(
                        dedupeKey,
                        tenantId.get(),
                        SenderMessageOutcomeV1.EVENT_TYPE_CLICKED,
                        messageId.get(),
                        MessageChannel.EMAIL,
                        providerMessageId,
                        at(event.path("click"), mailTime),
                        null,
                        null,
                        null);
            case "Reject", "Rendering Failure", "RenderingFailure" ->
                // SES refused to send (a virus, a broken template). The address is not at fault.
                mapped(
                        dedupeKey,
                        tenantId.get(),
                        SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED,
                        messageId.get(),
                        MessageChannel.EMAIL,
                        providerMessageId,
                        mailTime,
                        join(eventType, text(event.path("reject"), "reason"), null),
                        false,
                        recipientAddress(MissingNode.getInstance(), mail));
            default -> new Ignored("SES " + eventType + " is not an FI-2 outcome");
        };
    }

    private Mapping mapSms(@Nullable String dedupeKey, JsonNode event, String eventType) {
        String providerMessageId = text(event, "messageId");
        JsonNode context = event.path("context");
        Optional<UUID> tenantId = uuid(text(context, MessageTransport.TAG_TENANT_ID));
        Optional<UUID> messageId = uuid(text(context, MessageTransport.TAG_MESSAGE_ID));
        if (providerMessageId == null || tenantId.isEmpty() || messageId.isEmpty()) {
            return new Unusable("SMS " + eventType + " event without a messageId or the sender's context");
        }
        JsonNode timestamp = event.path("eventTimestamp");
        Instant occurredAt = timestamp.isNumber() ? Instant.ofEpochMilli(timestamp.longValue()) : null;
        String reason = join(eventType, text(event, "messageStatusDescription"), null);
        String address = text(event, "destinationPhoneNumber");
        if ("TEXT_DELIVERED".equals(eventType)
                || ("TEXT_SUCCESSFUL".equals(eventType) && event.path("isFinal").asBoolean(false))) {
            // TEXT_SUCCESSFUL is final only where the carrier returns no delivery receipt.
            return mapped(
                    dedupeKey,
                    tenantId.get(),
                    SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED,
                    messageId.get(),
                    MessageChannel.SMS,
                    providerMessageId,
                    occurredAt,
                    null,
                    null,
                    null);
        }
        if (SMS_HARD_BOUNCES.contains(eventType) || SMS_SOFT_BOUNCES.contains(eventType)) {
            return mapped(
                    dedupeKey,
                    tenantId.get(),
                    SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED,
                    messageId.get(),
                    MessageChannel.SMS,
                    providerMessageId,
                    occurredAt,
                    reason,
                    SMS_HARD_BOUNCES.contains(eventType),
                    address);
        }
        return new Ignored("SMS " + eventType + " is not an FI-2 outcome");
    }

    private static Mapping mapped(
            @Nullable String dedupeKey,
            UUID tenantId,
            String eventType,
            UUID messageId,
            MessageChannel channel,
            String providerMessageId,
            @Nullable Instant occurredAt,
            @Nullable String reason,
            @Nullable Boolean permanent,
            @Nullable String address) {
        if (occurredAt == null) {
            return new Unusable("Provider event " + eventType + " for " + providerMessageId + " has no timestamp");
        }
        boolean rejection = SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED.equals(eventType)
                || SenderMessageOutcomeV1.EVENT_TYPE_COMPLAINED.equals(eventType);
        if (rejection && address == null) {
            // FI-2 §2 requires the address on every bounce and complaint: it is what the CRM
            // suppression hand-off blocks. An event that names none is malformed, not relayable.
            return new Unusable(
                    "Provider event " + eventType + " for " + providerMessageId + " names no recipient address");
        }
        return new Mapped(
                dedupeKey,
                tenantId,
                eventType,
                new SenderMessageOutcomeV1(
                        messageId, channel.name(), providerMessageId, occurredAt, reason, permanent, address));
    }

    /** The {@code timestamp} of an SES sub-object, else the mail's own timestamp. */
    private static @Nullable Instant at(JsonNode node, @Nullable Instant fallback) {
        Instant parsed = instant(text(node, "timestamp"));
        return parsed == null ? fallback : parsed;
    }

    private static @Nullable Instant instant(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** SES tags are {@code name: [values]}. */
    private static @Nullable String firstTag(JsonNode mail, String name) {
        JsonNode values = mail.path("tags").path(name);
        if (values.isArray()) {
            return values.isEmpty() ? null : text(values.get(0));
        }
        return text(values);
    }

    private static Optional<UUID> uuid(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static @Nullable String email(@Nullable String address) {
        return AddressNormalizer.email(address).orElse(null);
    }

    /**
     * The recipient an SES bounce or complaint names, else the message's own destination
     * ({@code mail.destination}, which every SES event carries). The sender addresses one recipient
     * per message, so the two agree; the fallback covers an event whose recipient list is absent.
     */
    private static @Nullable String recipientAddress(JsonNode recipient, JsonNode mail) {
        String named = email(text(recipient, "emailAddress"));
        return named != null ? named : email(text(mail.path("destination").path(0)));
    }

    private static @Nullable String text(JsonNode node, String field) {
        return text(node.path(field));
    }

    private static @Nullable String text(@Nullable JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.stringValue();
        return value == null || value.isBlank() ? null : value;
    }

    private static @Nullable String join(@Nullable String first, @Nullable String second, @Nullable String third) {
        StringBuilder joined = new StringBuilder();
        for (String part : new String[] {first, second, third}) {
            if (part != null) {
                if (!joined.isEmpty()) {
                    joined.append(": ");
                }
                joined.append(part);
            }
        }
        return joined.isEmpty() ? null : joined.toString();
    }
}
