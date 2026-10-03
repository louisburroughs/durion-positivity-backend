package com.positivity.platformsender.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.platformsender.internal.dto.SendMessageRequest;
import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import com.positivity.platformsender.internal.entity.SentMessage;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.enums.SentMessageStatus;
import com.positivity.platformsender.internal.exception.MessageRefusedException;
import com.positivity.platformsender.internal.repository.ExtCustomerPersonPartyRepository;
import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.platformsender.internal.repository.SentMessageRepository;
import com.positivity.platformsender.internal.service.MessageSendService;
import com.positivity.platformsender.internal.service.MessageSendService.SendResult;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The send path end to end on Postgres, through the real service, replicas and (log) transport:
 * the claim and its settlement persist under row-level security with their audit columns filled by
 * JPA auditing, and a replay answers from the stored row.
 */
@DisplayName("Message send on Postgres (FI-2 §1)")
class MessageSendIT extends PostgresTenancyTestBase {

    private static final Instant SEEDED = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MessageSendService messageSendService;

    @Autowired
    private SentMessageRepository sentMessages;

    @Autowired
    private ExtCustomerPersonPartyRepository personParties;

    @Autowired
    private ExtPeopleContactPersonRepository persons;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seedPersonParty(String email) {
        UUID partyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        asTenant(TENANT_A, () -> {
            personParties.saveAndFlush(ExtCustomerPersonParty.builder()
                    .partyId(partyId)
                    .personId(personId)
                    .aggregateVersion(1)
                    .updatedAt(SEEDED)
                    .build());
            persons.saveAndFlush(ExtPeopleContactPerson.builder()
                    .personId(personId)
                    .email(email)
                    .aggregateVersion(1)
                    .updatedAt(SEEDED)
                    .build());
            return null;
        });
        return partyId;
    }

    private static SendMessageRequest email(UUID messageId, UUID partyId) {
        return new SendMessageRequest(
                messageId, MessageChannel.EMAIL, partyId, null, "SPRING24", "Spring tyres", "Time for new tyres");
    }

    @Test
    void acceptsOnceAndAnswersTheReplayFromTheStoredRow() {
        UUID partyId = seedPersonParty("Ada@Example.com");
        UUID messageId = UUID.randomUUID();

        SendResult first = asTenant(TENANT_A, () -> messageSendService.send(email(messageId, partyId)));
        SendResult replay = asTenant(TENANT_A, () -> messageSendService.send(email(messageId, partyId)));

        assertThat(first.replay()).isFalse();
        assertThat(first.response().providerMessageId()).isEqualTo("log-" + messageId);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());

        SentMessage row =
                asTenant(TENANT_A, () -> sentMessages.findByMessageId(messageId).orElseThrow());
        assertThat(row.getStatus()).isEqualTo(SentMessageStatus.ACCEPTED);
        assertThat(row.getTenantId()).isEqualTo(TENANT_A);
        assertThat(row.getCreatedAt()).as("JPA auditing fills created_at").isNotNull();
        assertThat(row.getUpdatedAt()).as("JPA auditing fills updated_at").isNotNull();
    }

    @Test
    void recordsAnUnresolvableRecipientAsRejected() {
        UUID messageId = UUID.randomUUID();

        assertThatExceptionOfType(MessageRefusedException.class)
                .isThrownBy(() ->
                        asTenant(TENANT_A, () -> messageSendService.send(email(messageId, UUID.randomUUID()))))
                .satisfies(e -> assertThat(e.getCode()).isEqualTo("RECIPIENT_NOT_REPLICATED"));

        assertThat(asTenant(
                        TENANT_A,
                        () -> sentMessages
                                .findByMessageId(messageId)
                                .orElseThrow()
                                .getStatus()))
                .isEqualTo(SentMessageStatus.REJECTED);
    }
}
