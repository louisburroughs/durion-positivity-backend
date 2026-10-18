package com.positivity.accounting.internal.billintake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.billintake.config.BillIntakeProperties;
import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.entity.BillSourceFile;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.billintake.repository.BillSourceFileRepository;
import com.positivity.accounting.internal.exception.VendorBillException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("BillIntakeEntry: receive an upload (S25 #2518)")
class BillIntakeEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");
    private static final UUID REQUEST = UUID.fromString("019a0000-0000-7000-8000-000000000001");
    private static final byte[] PDF = "%PDF-1.7 a bill".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0};

    private final BillIntakeItemRepository items = mock(BillIntakeItemRepository.class);
    private final BillSourceFileRepository sourceFiles = mock(BillSourceFileRepository.class);
    private final FileStore fileStore = mock(FileStore.class);
    private final BillIntakeReferences references = mock(BillIntakeReferences.class);
    private final BillIntakeAuditRecorder audit = mock(BillIntakeAuditRecorder.class);
    private final List<BillIntakeItem> saved = new ArrayList<>();
    private BillIntakeEntry entry;
    private int counter;

    @BeforeEach
    void setUp() {
        entry = new BillIntakeEntry(
                items,
                sourceFiles,
                fileStore,
                references,
                audit,
                new BillIntakeProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(references.next()).thenAnswer(inv -> "BI-202610-000000" + (++counter));
        when(items.saveAndFlush(any())).thenAnswer(inv -> {
            BillIntakeItem item = inv.getArgument(0);
            item.setIntakeId(UUID.randomUUID());
            saved.add(item);
            return item;
        });
        when(fileStore.quarantine(any(), any(), any(), any())).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(0);
            return new FileStore.StoredFile(UUID.randomUUID(), DbFileStore.sha256(bytes), bytes.length, inv.getArgument(1));
        });
        when(fileStore.recordRejected(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            byte[] bytes = inv.getArgument(0);
            return new FileStore.StoredFile(UUID.randomUUID(), DbFileStore.sha256(bytes), bytes.length, inv.getArgument(1));
        });
    }

    private static BillIntakeEntry.IncomingFile file(byte[] bytes) {
        return new BillIntakeEntry.IncomingFile(bytes, "application/pdf", "bill.pdf");
    }

    @Test
    @DisplayName("a PDF is stored apart and the draft is QUARANTINED, audited with its sha-256")
    void quarantinesAPdf() {
        var received = entry.receive(List.of(file(PDF)), BillIntakeChannel.UPLOAD, REQUEST, "clerk.carol");

        assertThat(received.replayed()).isFalse();
        assertThat(received.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(BillIntakeStatus.QUARANTINED);
            assertThat(item.intakeReference()).isEqualTo("BI-202610-0000001");
            assertThat(item.sha256()).isEqualTo(DbFileStore.sha256(PDF));
        });
        verify(fileStore).quarantine(any(), eq("application/pdf"), eq("bill.pdf"), eq("clerk.carol"));
        assertThat(saved.get(0).getUploadedBy()).isEqualTo("clerk.carol");
        assertThat(saved.get(0).getUploadedAt()).isEqualTo(NOW);
        ArgumentCaptor<String> newValue = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), eq("UPLOAD"), eq("clerk.carol"), any(), any(), newValue.capture());
        assertThat(newValue.getValue()).contains(DbFileStore.sha256(PDF)).doesNotContain("bill.pdf");
    }

    @Test
    @DisplayName("an executable named bill.pdf is FILE_REJECTED / TYPE_NOT_ALLOWED at once, nothing stored or scanned")
    void rejectsByMagicBytes() {
        var received = entry.receive(List.of(file(EXE)), BillIntakeChannel.UPLOAD, REQUEST, "clerk.carol");

        assertThat(received.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(BillIntakeStatus.FILE_REJECTED);
            assertThat(item.rejectionReason()).isEqualTo("TYPE_NOT_ALLOWED");
        });
        verify(fileStore, never()).quarantine(any(), any(), any(), any());
        verify(fileStore).recordRejected(any(), any(), any(), any(), eq(NOW.plusSeconds(30L * 24 * 3600)));
    }

    @Test
    @DisplayName("each file of a request gets its own draft under its own derived request id")
    void oneDraftPerFile() {
        var received = entry.receive(List.of(file(PDF), file(EXE)), BillIntakeChannel.UPLOAD, REQUEST, "clerk.carol");

        assertThat(received.items()).hasSize(2);
        assertThat(saved).extracting(BillIntakeItem::getRequestId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(saved.get(0).getRequestId()).isEqualTo(BillIntakeEntry.fileRequestId(REQUEST, 0));
        assertThat(saved.get(1).getRequestId()).isEqualTo(BillIntakeEntry.fileRequestId(REQUEST, 1));
    }

    private void stubFirstRequest(byte[] bytes, int files) {
        for (int i = 0; i < files; i++) {
            BillIntakeItem item = new BillIntakeItem();
            item.setIntakeId(UUID.randomUUID());
            item.setIntakeReference("BI-202610-000000" + (i + 1));
            item.setChannel(BillIntakeChannel.UPLOAD);
            item.setStatus(BillIntakeStatus.QUARANTINED);
            item.setUploadedAt(NOW);
            item.setSourceFileId(UUID.randomUUID());
            BillSourceFile source = new BillSourceFile();
            source.setSha256(DbFileStore.sha256(bytes));
            when(items.findByChannelAndRequestId(BillIntakeChannel.UPLOAD, BillIntakeEntry.fileRequestId(REQUEST, i)))
                    .thenReturn(Optional.of(item));
            when(sourceFiles.findById(item.getSourceFileId())).thenReturn(Optional.of(source));
        }
    }

    @Test
    @DisplayName("a replay of the same request id and files returns the first response and stores nothing")
    void replay() {
        stubFirstRequest(PDF, 1);

        var received = entry.receive(List.of(file(PDF)), BillIntakeChannel.UPLOAD, REQUEST, "clerk.carol");

        assertThat(received.replayed()).isTrue();
        assertThat(received.items()).singleElement().satisfies(item -> assertThat(item.intakeReference())
                .isEqualTo("BI-202610-0000001"));
        verify(fileStore, never()).quarantine(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the same request id with other content, or more files, is 409 IDEMPOTENCY_CONFLICT")
    void conflict() {
        stubFirstRequest(PDF, 1);
        byte[] other = "%PDF-1.4 another".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> entry.receive(List.of(file(other)), BillIntakeChannel.UPLOAD, REQUEST, "a"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VendorBillException.Code.IDEMPOTENCY_CONFLICT));
        // The first request had one file: a replay with two is another request.
        assertThatThrownBy(() -> entry.receive(List.of(file(PDF), file(PDF)), BillIntakeChannel.UPLOAD, REQUEST, "a"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VendorBillException.Code.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("no file, eleven files, or a file over 25 MB are refused before anything is stored")
    void limits() {
        assertThatThrownBy(() -> entry.receive(List.of(), BillIntakeChannel.UPLOAD, REQUEST, "a"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
        List<BillIntakeEntry.IncomingFile> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(file(PDF));
        }
        assertThatThrownBy(() -> entry.receive(eleven, BillIntakeChannel.UPLOAD, REQUEST, "a"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
        byte[] big = new byte[25 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> entry.receive(List.of(file(big)), BillIntakeChannel.UPLOAD, REQUEST, "a"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VendorBillException.Code.PAYLOAD_TOO_LARGE));
        verify(fileStore, never()).quarantine(any(), any(), any(), any());
    }
}
