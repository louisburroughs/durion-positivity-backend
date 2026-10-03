package com.positivity.platformsender.internal.service;

import com.positivity.platformsender.internal.dto.SendMessageRequest;
import com.positivity.platformsender.internal.dto.SendMessageResponse;
import com.positivity.platformsender.internal.entity.SentMessage;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.enums.SentMessageStatus;
import com.positivity.platformsender.internal.exception.MessageRefusedException;
import com.positivity.platformsender.internal.exception.SenderUnavailableException;
import com.positivity.platformsender.internal.repository.SentMessageRepository;
import com.positivity.tenancy.TenantResolver;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FI-2 §1 send, at most once per {@code messageId}.
 *
 * <p>The key is claimed first, in its own committed transaction (a {@code PENDING} row), and the
 * provider is called with no transaction open, so no connection is held across the network call.
 * The outcome then settles the row: {@code ACCEPTED} with the provider's id, {@code REJECTED} with
 * the refusal, or, on a transient failure, the claim is released so the caller's retry can try
 * again. A replay reads the settled row and answers exactly as the first request was answered.
 *
 * <p>A crash between the provider accepting and the row settling leaves the claim {@code PENDING},
 * and so does a provider call that may have been delivered without an answer coming back (a read
 * timeout: {@code UNCERTAIN}).
 * That row answers every replay as transient until the caller's bounded retry gives up: the message
 * may then be recorded as failed although it was delivered, which is the side FI-2's "a replayed
 * messageId MUST NOT produce a second delivery" leaves to err on. Neither SES nor End User Messaging
 * takes a client token, so the provider cannot dedupe for us.
 */
@Slf4j
@Service
public class MessageSendServiceImpl implements MessageSendService {

    static final String CODE_SUBJECT_REQUIRED = "SUBJECT_REQUIRED";
    static final String CODE_IN_FLIGHT = "SEND_IN_FLIGHT";
    private static final int MAX_REASON_LENGTH = 1000;

    private final SentMessageRepository sentMessageRepository;
    private final RecipientAddressResolver addressResolver;
    private final MessageTransport transport;
    private final TenantResolver tenantResolver;
    private final TransactionTemplate transaction;

    public MessageSendServiceImpl(
            SentMessageRepository sentMessageRepository,
            RecipientAddressResolver addressResolver,
            MessageTransport transport,
            TenantResolver tenantResolver,
            PlatformTransactionManager transactionManager) {
        this.sentMessageRepository = sentMessageRepository;
        this.addressResolver = addressResolver;
        this.transport = transport;
        this.tenantResolver = tenantResolver;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public @NonNull SendResult send(@NonNull SendMessageRequest request) {
        if (request.channel() == MessageChannel.EMAIL
                && (request.subject() == null || request.subject().isBlank())) {
            throw new MessageRefusedException(CODE_SUBJECT_REQUIRED, "An EMAIL message needs a subject");
        }

        Optional<SentMessage> earlier =
                transaction.execute(_ -> sentMessageRepository.findByMessageId(request.messageId()));
        if (earlier != null && earlier.isPresent()) {
            return replay(earlier.get());
        }
        SentMessage claim;
        try {
            claim = claim(request);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request claimed the key between the read above and this insert.
            Optional<SentMessage> winner =
                    transaction.execute(_ -> sentMessageRepository.findByMessageId(request.messageId()));
            if (winner != null && winner.isPresent()) {
                return replay(winner.get());
            }
            throw new SenderUnavailableException(
                    CODE_IN_FLIGHT, "messageId " + request.messageId() + " is being sent by a concurrent request");
        }

        RecipientAddressResolver.Resolution resolution;
        try {
            resolution = addressResolver.resolve(request.channel(), request.recipientPartyId(), request.contactId());
        } catch (RuntimeException e) {
            // Nothing was sent: free the key rather than leave a claim no retry can get past.
            release(claim);
            throw e;
        }
        if (resolution.address() == null) {
            RecipientAddressResolver.Unresolved reason = resolution.unresolved();
            String code = reason == null ? "NO_RESOLVABLE_ADDRESS" : reason.name();
            String message = "No deliverable " + request.channel() + " address for party " + request.recipientPartyId();
            settle(claim, SentMessageStatus.REJECTED, null, null, code, message);
            throw new MessageRefusedException(code, message);
        }
        String address = resolution.address();
        String addressHash = AddressNormalizer.hash(address);

        MessageTransport.TransportResult result;
        try {
            result = transport.send(new MessageTransport.OutboundMessage(
                    request.messageId(),
                    tenantResolver.require(),
                    request.channel(),
                    address,
                    request.campaignCode(),
                    request.subject(),
                    request.body()));
        } catch (RuntimeException e) {
            // Transports classify every provider answer; an exception here is a fault of ours. Free
            // the key so a retry can run once the fault is fixed, and let the 500 tell the caller.
            release(claim);
            throw e;
        }

        return switch (result.kind()) {
            case ACCEPTED -> {
                String providerMessageId = result.providerMessageId();
                settle(claim, SentMessageStatus.ACCEPTED, providerMessageId, addressHash, null, null);
                log.info(
                        "Accepted {} message {} as {} (campaign {})",
                        request.channel(),
                        request.messageId(),
                        providerMessageId,
                        request.campaignCode());
                yield new SendResult(new SendMessageResponse(providerMessageId, addressHash), false);
            }
            case PERMANENT_FAILURE -> {
                settle(claim, SentMessageStatus.REJECTED, null, addressHash, result.code(), result.reason());
                throw new MessageRefusedException(codeOf(result), reasonOf(result));
            }
            case TRANSIENT_FAILURE -> {
                release(claim);
                throw new SenderUnavailableException(codeOf(result), reasonOf(result));
            }
            case UNCERTAIN -> {
                // The provider may have taken it. Keep the claim PENDING so no retry can send it
                // again; the caller sees a transient failure now and SEND_IN_FLIGHT on every replay.
                log.warn(
                        "{} message {} may have been delivered (no provider answer): {}",
                        request.channel(),
                        request.messageId(),
                        reasonOf(result));
                throw new SenderUnavailableException(codeOf(result), reasonOf(result));
            }
        };
    }

    private SendResult replay(SentMessage earlier) {
        return switch (earlier.getStatus()) {
            case ACCEPTED ->
                new SendResult(new SendMessageResponse(earlier.getProviderMessageId(), earlier.getAddressHash()), true);
            case REJECTED ->
                throw new MessageRefusedException(
                        earlier.getFailureCode() == null ? "REJECTED" : earlier.getFailureCode(),
                        earlier.getFailureReason() == null ? "Message was refused" : earlier.getFailureReason());
            case PENDING ->
                throw new SenderUnavailableException(
                        CODE_IN_FLIGHT,
                        "An earlier request with messageId " + earlier.getMessageId() + " is unsettled");
        };
    }

    /**
     * Commits a {@code PENDING} row under the key.
     *
     * @throws DataIntegrityViolationException when a concurrent request holds the key
     */
    private SentMessage claim(SendMessageRequest request) {
        SentMessage claimed = transaction.execute(_ -> sentMessageRepository.saveAndFlush(SentMessage.builder()
                .messageId(request.messageId())
                .channel(request.channel())
                .recipientPartyId(request.recipientPartyId())
                .contactId(request.contactId())
                .campaignCode(request.campaignCode())
                .status(SentMessageStatus.PENDING)
                .build()));
        if (claimed == null) {
            throw new IllegalStateException("Claim for messageId " + request.messageId() + " returned nothing");
        }
        return claimed;
    }

    private void settle(
            SentMessage claim,
            SentMessageStatus status,
            String providerMessageId,
            String addressHash,
            String failureCode,
            String failureReason) {
        transaction.executeWithoutResult(_ -> {
            SentMessage row = sentMessageRepository
                    .findById(claim.getSentMessageId())
                    .orElseThrow(() -> new IllegalStateException("Claim " + claim.getSentMessageId() + " vanished"));
            row.setStatus(status);
            row.setProviderMessageId(providerMessageId);
            row.setAddressHash(addressHash);
            row.setFailureCode(failureCode);
            row.setFailureReason(truncate(failureReason));
            sentMessageRepository.save(row);
        });
    }

    private void release(SentMessage claim) {
        transaction.executeWithoutResult(_ -> sentMessageRepository.deleteById(claim.getSentMessageId()));
    }

    private static String codeOf(MessageTransport.TransportResult result) {
        return result.code() == null ? "PROVIDER_ERROR" : result.code();
    }

    private static String reasonOf(MessageTransport.TransportResult result) {
        return result.reason() == null ? "The provider did not accept the message" : result.reason();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }
}
