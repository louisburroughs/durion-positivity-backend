package com.positivity.platformsender.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import com.positivity.platformsender.internal.entity.SentMessage;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.enums.SentMessageStatus;
import com.positivity.platformsender.internal.repository.ExtCustomerPersonPartyRepository;
import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.platformsender.internal.repository.SentMessageRepository;
import com.positivity.platformsender.internal.service.RecipientAddressResolver;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the isolation, not just the mapping: a send record written as tenant A is invisible to
 * tenant B through the repository and through raw SQL, an unbound connection can neither read nor
 * write it, the idempotency key is unique per tenant rather than platform-wide, and an address
 * replicated for one tenant never resolves for another.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-platform-sender)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Autowired
    private SentMessageRepository sentMessages;

    @Autowired
    private ExtCustomerPersonPartyRepository personParties;

    @Autowired
    private ExtPeopleContactPersonRepository persons;

    @Autowired
    private RecipientAddressResolver resolver;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aSendRecordWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID messageId = UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A, () -> sentMessages.saveAndFlush(sent(messageId)).getSentMessageId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(sentMessages.findByMessageId(messageId)).isPresent();
            assertThat(sentMessages.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countById(jdbc, id)).isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(sentMessages.findByMessageId(messageId))
                    .as("a replay in another tenant does not see this tenant's send")
                    .isEmpty();
            assertThat(countById(jdbc, id)).as("RLS hides it from raw SQL too").isZero();
            assertThat(jdbc.update("UPDATE sent_message SET status = 'REJECTED' WHERE sent_message_id = ?", id))
                    .isZero();
            assertThat(sentMessages.saveAndFlush(sent(messageId)).getTenantId())
                    .as("the idempotency key is unique per tenant, not across the platform")
                    .isEqualTo(TENANT_B);
        });

        assertThat(countById(jdbc, id)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO sent_message (sent_message_id, message_id, channel, recipient_party_id,"
                                + " campaign_code, status, created_at, updated_at)"
                                + " VALUES (?, ?, 'SMS', ?, 'X', 'PENDING', now(), now())",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(sentMessages.findById(id).orElseThrow().getStatus())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo(SentMessageStatus.ACCEPTED));
    }

    @Test
    void anAddressReplicatedForOneTenantNeverResolvesForAnother() {
        UUID partyId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        asTenant(TENANT_A, () -> {
            personParties.saveAndFlush(ExtCustomerPersonParty.builder()
                    .partyId(partyId)
                    .personId(personId)
                    .aggregateVersion(1)
                    .updatedAt(NOW)
                    .build());
            persons.saveAndFlush(ExtPeopleContactPerson.builder()
                    .personId(personId)
                    .email(" Ada@Example.com ")
                    .mobilePhone("(555) 010-0100")
                    .aggregateVersion(1)
                    .updatedAt(NOW)
                    .build());
            return null;
        });

        asTenant(TENANT_A, () -> {
            assertThat(resolver.resolve(MessageChannel.EMAIL, partyId, null).address())
                    .isEqualTo("ada@example.com");
            assertThat(resolver.resolve(MessageChannel.SMS, partyId, partyId).address())
                    .isEqualTo("+15550100100");
        });
        asTenant(
                TENANT_B,
                () -> assertThat(resolver.resolve(MessageChannel.EMAIL, partyId, null)
                                .unresolved())
                        .isEqualTo(RecipientAddressResolver.Unresolved.RECIPIENT_NOT_REPLICATED));
    }

    private static SentMessage sent(UUID messageId) {
        return SentMessage.builder()
                .messageId(messageId)
                .channel(MessageChannel.EMAIL)
                .recipientPartyId(UUID.randomUUID())
                .campaignCode("SPRING24")
                .status(SentMessageStatus.ACCEPTED)
                .providerMessageId("ses-" + messageId)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static int countById(JdbcTemplate jdbc, UUID id) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM sent_message WHERE sent_message_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
