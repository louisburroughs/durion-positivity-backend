package com.positivity.inboundmail.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.inboundmail.internal.dto.StagedAttachmentBytes;
import com.positivity.inboundmail.internal.dto.StagedAttachmentRef;
import com.positivity.inboundmail.internal.entity.StagedAttachment;
import com.positivity.inboundmail.internal.entity.StagedAttachmentContent;
import com.positivity.inboundmail.internal.repository.StagedAttachmentContentRepository;
import com.positivity.inboundmail.internal.repository.StagedAttachmentRepository;
import com.positivity.tenancy.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("DbAttachmentStaging: stage, fetch, release and purge")
class DbAttachmentStagingTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    // sha256("hello")
    private static final String HELLO_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    private final StagedAttachmentRepository attachments = mock(StagedAttachmentRepository.class);
    private final StagedAttachmentContentRepository contents = mock(StagedAttachmentContentRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private DbAttachmentStaging staging;

    @BeforeEach
    void setUp() {
        staging = new DbAttachmentStaging(
                attachments,
                contents,
                new InboundMailProperties("s", "none", "mail.example.com", Duration.ofDays(7), "x", 10L, 200, 2),
                Clock.fixed(NOW, ZoneOffset.UTC),
                jdbc,
                tenantResolver,
                mock(PlatformTransactionManager.class));
        when(tenantResolver.require()).thenReturn(TENANT);
    }

    private static StagedAttachment staged(UUID ref, Instant collectedAt) {
        return StagedAttachment.builder()
                .ref(ref)
                .inboundMessageId(UUID.randomUUID())
                .fileName("invoice.pdf")
                .declaredType("application/pdf")
                .sizeBytes(5)
                .sha256(HELLO_SHA)
                .createdAt(NOW.minus(Duration.ofDays(9)))
                .collectedAt(collectedAt)
                .build();
    }

    @Test
    @DisplayName("staging records the size and the sha256 of exactly the bytes it stores")
    void stagesWithDigest() {
        UUID ref = UUID.randomUUID();
        when(attachments.saveAndFlush(any(StagedAttachment.class))).thenAnswer(invocation -> {
            StagedAttachment saved = invocation.getArgument(0);
            saved.setRef(ref);
            return saved;
        });
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        StagedAttachmentRef result = staging.stage(UUID.randomUUID(), "invoice.pdf", "application/pdf", bytes);

        assertThat(result.ref()).isEqualTo(ref);
        assertThat(result.sizeBytes()).isEqualTo(5);
        assertThat(result.sha256()).isEqualTo(HELLO_SHA);
        ArgumentCaptor<StagedAttachmentContent> content = ArgumentCaptor.forClass(StagedAttachmentContent.class);
        verify(contents).save(content.capture());
        assertThat(content.getValue().getRef()).isEqualTo(ref);
        assertThat(content.getValue().getContent()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("an attachment over the configured cap is refused before anything is stored")
    void refusesOversize() {
        assertThatThrownBy(() -> staging.stage(UUID.randomUUID(), "a.pdf", "application/pdf", new byte[11]))
                .isInstanceOf(IllegalArgumentException.class);
        verify(attachments, never()).saveAndFlush(any());
        verify(contents, never()).save(any());
    }

    @Test
    @DisplayName("fetch returns the bytes with the staged size and digest")
    void fetchesLiveAttachment() {
        UUID ref = UUID.randomUUID();
        when(attachments.findById(ref)).thenReturn(Optional.of(staged(ref, null)));
        when(contents.findById(ref))
                .thenReturn(Optional.of(new StagedAttachmentContent(ref, "hello".getBytes(StandardCharsets.UTF_8))));

        Optional<StagedAttachmentBytes> fetched = staging.fetch(ref);

        assertThat(fetched).isPresent();
        assertThat(fetched.get().sha256()).isEqualTo(HELLO_SHA);
        assertThat(fetched.get().sizeBytes()).isEqualTo(5);
        assertThat(fetched.get().toString()).doesNotContain("hello");
    }

    @Test
    @DisplayName("unknown, collected and byte-less refs are all simply absent")
    void absentWhenUnknownCollectedOrPurged() {
        UUID unknown = UUID.randomUUID();
        UUID collected = UUID.randomUUID();
        UUID purged = UUID.randomUUID();
        when(attachments.findById(unknown)).thenReturn(Optional.empty());
        when(attachments.findById(collected)).thenReturn(Optional.of(staged(collected, NOW)));
        when(attachments.findById(purged)).thenReturn(Optional.of(staged(purged, null)));
        when(contents.findById(purged)).thenReturn(Optional.empty());

        assertThat(staging.fetch(unknown)).isEmpty();
        assertThat(staging.fetch(collected)).isEmpty();
        assertThat(staging.fetch(purged)).isEmpty();
    }

    @Test
    @DisplayName("release is two bulk statements scoped to the tenant and the message: mark collected, delete bytes")
    void releaseIsBulkSql() {
        UUID messageId = UUID.randomUUID();
        when(jdbc.update(startsWith("DELETE FROM staged_attachment_content"), any(Object[].class))).thenReturn(2);

        assertThat(staging.release(messageId)).isEqualTo(2);

        ArgumentCaptor<Object[]> update = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("UPDATE staged_attachment SET collected_at"), update.capture());
        assertThat(update.getValue()).contains(TENANT, messageId);
        verify(attachments, never()).findByInboundMessageId(any());
        verify(contents, never()).deleteById(any());
    }

    @Test
    @DisplayName("purge deletes in bounded batches until a batch removes nothing, never loading an entity")
    void purgeLoopsInBatches() {
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        when(jdbc.update(startsWith("DELETE FROM staged_attachment WHERE"), any(Object[].class)))
                .thenReturn(200)
                .thenReturn(7)
                .thenReturn(0);

        assertThat(staging.purgeStagedBefore(cutoff)).isEqualTo(207);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(3)).update(startsWith("DELETE FROM staged_attachment WHERE"), args.capture());
        assertThat(args.getValue()).containsExactly(TENANT, TENANT, java.sql.Timestamp.from(cutoff), 200);
        verify(attachments, never()).findByCreatedAtBefore(any());
        verify(attachments, never()).delete(any());
    }
}
