package com.positivity.inboundmail.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import com.positivity.inboundmail.internal.dto.StagedAttachmentRef;
import com.positivity.inboundmail.internal.entity.InboundAddress;
import com.positivity.inboundmail.internal.entity.InboundMessage;
import com.positivity.inboundmail.internal.enums.AddressStatus;
import com.positivity.inboundmail.internal.enums.MessageStatus;
import com.positivity.inboundmail.internal.repository.InboundAddressRepository;
import com.positivity.inboundmail.internal.repository.InboundMessageRepository;
import com.positivity.inboundmail.internal.repository.StagedAttachmentContentRepository;
import com.positivity.inboundmail.internal.repository.StagedAttachmentRepository;
import com.positivity.inboundmail.internal.service.AddressRegistry;
import com.positivity.inboundmail.internal.service.AttachmentStaging;
import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.service.AddressGenerator;
import com.positivity.inboundmail.internal.service.AddressRegistryImpl;
import com.positivity.inboundmail.internal.service.StagingPurgeJob;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Proves the isolation, not just the mapping: staged rows written as tenant A are invisible to tenant B
 * through the repositories, the port and raw SQL, an unbound connection can neither read nor write them,
 * the message key is unique per tenant rather than platform-wide, and the address registry, the one
 * global table, resolves an address with no tenant bound.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-inbound-mail)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Autowired
    private AddressRegistry registry;

    @Autowired
    private AttachmentStaging staging;

    @Autowired
    private InboundAddressRepository addresses;

    @Autowired
    private InboundMessageRepository messages;

    @Autowired
    private StagedAttachmentRepository attachments;

    @Autowired
    private StagedAttachmentContentRepository contents;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantResolver tenantResolver;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** A message key as Part B will compute it: 64 lower-case hex characters. */
    private static String key() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private UUID messageFor(UUID tenant, String key) {
        return asTenant(tenant, () -> {
            IssuedAddressResponse address = registry.issueOrGet();
            return messages.saveAndFlush(InboundMessage.builder()
                            .addressId(address.addressId())
                            .messageKey(key)
                            .receivedAt(NOW)
                            .status(MessageStatus.STAGED)
                            .build())
                    .getInboundMessageId();
        });
    }

    @Test
    void aStagedAttachmentIsInvisibleToAnotherTenantAndToNoTenant() {
        UUID messageId = messageFor(TENANT_A, key());
        StagedAttachmentRef ref = asTenant(
                TENANT_A,
                () -> staging.stage(
                        messageId, "invoice.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8)));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(staging.fetch(ref.ref())).isPresent();
            assertThat(attachments.findById(ref.ref()).orElseThrow().getTenantId())
                    .isEqualTo(TENANT_A);
            assertThat(count(jdbc, "staged_attachment", ref.ref())).isEqualTo(1);
            assertThat(count(jdbc, "staged_attachment_content", ref.ref())).isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(staging.fetch(ref.ref()))
                    .as("another tenant's ref reads as absent")
                    .isEmpty();
            assertThat(attachments.findById(ref.ref())).isEmpty();
            assertThat(contents.existsById(ref.ref())).isFalse();
            assertThat(count(jdbc, "staged_attachment", ref.ref()))
                    .as("RLS hides it from raw SQL")
                    .isZero();
            assertThat(count(jdbc, "staged_attachment_content", ref.ref())).isZero();
            assertThat(jdbc.update("DELETE FROM staged_attachment_content WHERE ref = ?", ref.ref()))
                    .as("nor can tenant B delete it")
                    .isZero();
        });

        assertThat(count(jdbc, "staged_attachment_content", ref.ref()))
                .as("no tenant bound: empty")
                .isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO staged_attachment_content (ref, content) VALUES (?, ?)",
                        UUID.randomUUID(),
                        new byte[] {1}))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(TENANT_A, () -> assertThat(staging.fetch(ref.ref())).isPresent());
    }

    @Test
    void theMessageKeyIsUniquePerTenantNotAcrossThePlatform() {
        String key = key();
        messageFor(TENANT_A, key);

        assertThat(messageFor(TENANT_B, key))
                .as("the same Message-ID is a different message for another tenant")
                .isNotNull();
        assertThatThrownBy(() -> messageFor(TENANT_A, key))
                .as("a repeat inside one tenant is the idempotency collision")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theRegistryResolvesAnAddressWithNoTenantBound() {
        IssuedAddressResponse issued = asTenant(TENANT_A, registry::issueOrGet);

        TenantContext.clear();
        InboundAddress resolved = addresses.findByAddress(issued.address()).orElseThrow();

        assertThat(resolved.getTenantId()).isEqualTo(TENANT_A);
        assertThat(resolved.getAddressId()).isEqualTo(issued.addressId());
    }

    @Test
    void eachTenantHasOneActiveAddressAndTheDatabaseEnforcesIt() {
        IssuedAddressResponse a = asTenant(TENANT_A, registry::issueOrGet);
        IssuedAddressResponse b = asTenant(TENANT_B, registry::issueOrGet);

        assertThat(a.address()).isNotEqualTo(b.address());
        assertThat(asTenant(TENANT_A, registry::issueOrGet).addressId()).isEqualTo(a.addressId());
        assertThatThrownBy(() -> addresses.saveAndFlush(InboundAddress.builder()
                        .address("bills-zzzzzzzzzzzzzzzzzzzzzzzzzz@mail.invalid")
                        .tenantId(TENANT_A)
                        .status(AddressStatus.ACTIVE)
                        .build()))
                .as("a second ACTIVE row for the tenant violates the partial unique index")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rotationRetiresTheOldAddressAndIsIdempotentOnTheRequestId() {
        UUID requestId = UUID.randomUUID();
        IssuedAddressResponse before = asTenant(TENANT_B, registry::issueOrGet);

        IssuedAddressResponse rotated = asTenant(TENANT_B, () -> registry.rotate(requestId));
        IssuedAddressResponse replay = asTenant(TENANT_B, () -> registry.rotate(requestId));

        assertThat(rotated.addressId()).isNotEqualTo(before.addressId());
        assertThat(replay.addressId()).as("a replay never rotates twice").isEqualTo(rotated.addressId());
        assertThat(replay.address()).isEqualTo(rotated.address());
        assertThat(addresses.findById(before.addressId()).orElseThrow().getStatus())
                .isEqualTo(AddressStatus.RETIRED);
        assertThat(addresses.findById(before.addressId()).orElseThrow().getRetiredAt())
                .isNotNull();
        assertThat(asTenant(TENANT_B, registry::issueOrGet).addressId()).isEqualTo(rotated.addressId());
    }

    private UUID stageAt(UUID tenant, UUID messageId, Instant createdAt) {
        return asTenant(tenant, () -> {
            UUID ref = staging.stage(messageId, "f.pdf", "application/pdf", new byte[] {1, 2, 3})
                    .ref();
            new JdbcTemplate(dataSource)
                    .update("UPDATE staged_attachment SET created_at = ? WHERE ref = ?", Timestamp.from(createdAt), ref);
            return ref;
        });
    }

    @Test
    void thePurgeJobRemovesOnlyEachTenantsExpiredRowsAndBytes() {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(30));
        UUID messageA = messageFor(TENANT_A, key());
        UUID messageB = messageFor(TENANT_B, key());
        List<UUID> oldA = List.of(stageAt(TENANT_A, messageA, old), stageAt(TENANT_A, messageA, old), stageAt(TENANT_A, messageA, old));
        UUID freshA = stageAt(TENANT_A, messageA, now);
        UUID oldB = stageAt(TENANT_B, messageB, old);
        UUID freshB = stageAt(TENANT_B, messageB, now);

        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setTenants(List.of(TENANT_A, TENANT_B));
        StagingPurgeJob job = new StagingPurgeJob(
                staging,
                new TenantIterator(new StaticTenantRegistry(tenancy)),
                new InboundMailProperties(
                        "s", "none", "mail.invalid", Duration.ofDays(7), "x", 26_214_400L, 200, 2),
                Clock.fixed(now, ZoneOffset.UTC));

        job.purgeExpiredAttachments();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            for (UUID ref : oldA) {
                assertThat(staging.fetch(ref)).as("expired A row is gone").isEmpty();
                assertThat(count(jdbc, "staged_attachment", ref)).isZero();
                assertThat(count(jdbc, "staged_attachment_content", ref)).as("and its bytes").isZero();
            }
            assertThat(staging.fetch(freshA)).as("a fresh A row stays").isPresent();
            assertThat(count(jdbc, "staged_attachment_content", freshA)).isEqualTo(1);
        });
        asTenant(TENANT_B, () -> {
            assertThat(staging.fetch(oldB)).as("expired B row is gone").isEmpty();
            assertThat(count(jdbc, "staged_attachment_content", oldB)).isZero();
            assertThat(staging.fetch(freshB)).as("a fresh B row stays").isPresent();
            assertThat(count(jdbc, "staged_attachment_content", freshB)).isEqualTo(1);
        });
    }

    @Test
    void releaseDeletesTheBytesMarksCollectedAndLaterFetchesFindNothing() {
        UUID messageId = messageFor(TENANT_A, key());
        UUID other = messageFor(TENANT_A, key());
        UUID ref = stageAt(TENANT_A, messageId, Instant.now());
        UUID untouched = stageAt(TENANT_A, other, Instant.now());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        int released = asTenant(TENANT_A, () -> staging.release(messageId));

        assertThat(released).isEqualTo(1);
        asTenant(TENANT_A, () -> {
            assertThat(count(jdbc, "staged_attachment_content", ref)).as("bytes deleted").isZero();
            assertThat(attachments.findById(ref).orElseThrow().getCollectedAt()).isNotNull();
            assertThat(staging.fetch(ref)).as("a collected ref is a 404").isEmpty();
            assertThat(staging.fetch(untouched)).as("another message's attachment is untouched").isPresent();
            assertThat(staging.release(messageId)).as("a repeat releases nothing").isZero();
        });
        asTenant(TENANT_B, () -> assertThat(staging.release(messageId)).as("another tenant releases nothing").isZero());
    }

    @Test
    void theGlobalAddressKeyRefusesTheSameAddressTextForTwoTenants() {
        AddressRegistryImpl stubbed = new AddressRegistryImpl(
                addresses,
                new AddressGenerator(new java.security.SecureRandom() {
                    @Override
                    public int nextInt(int bound) {
                        return 0;
                    }
                }, "mail.invalid"),
                tenantResolver,
                Clock.systemUTC(),
                transactionManager);
        // 26 characters, all 'a' (nextInt always 0): the same text for every tenant that asks.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        IssuedAddressResponse issued = asTenant(first, stubbed::issueOrGet);
        assertThat(issued.address()).isEqualTo("bills-aaaaaaaaaaaaaaaaaaaaaaaaaa@mail.invalid");

        assertThatThrownBy(() -> asTenant(second, stubbed::issueOrGet))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("inbound_address_address_key");
    }

    @Test
    void aRetiredAddressStillResolvesButIsNeverIssuedAgain() {
        UUID tenant = UUID.randomUUID();
        IssuedAddressResponse original = asTenant(tenant, registry::issueOrGet);
        IssuedAddressResponse rotated = asTenant(tenant, () -> registry.rotate(UUID.randomUUID()));

        TenantContext.clear();
        InboundAddress retired = addresses.findByAddress(original.address()).orElseThrow();
        assertThat(retired.getStatus()).as("Part B answers ADDRESS_RETIRED from this").isEqualTo(AddressStatus.RETIRED);
        assertThat(retired.getTenantId()).isEqualTo(tenant);

        IssuedAddressResponse current = asTenant(tenant, registry::issueOrGet);
        assertThat(current.addressId()).isEqualTo(rotated.addressId()).isNotEqualTo(original.addressId());
        assertThat(current.address()).isNotEqualTo(original.address());
    }

    private static int count(JdbcTemplate jdbc, String table, UUID ref) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE ref = ?", Integer.class, ref);
        return count == null ? 0 : count;
    }
}
