package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.entity.TusUpload;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import com.positivity.bulkloader.internal.repository.TusUploadRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Resumable (TUS) uploads.
 *
 * <p>The protocol's whole point is that a client which lost its connection can ask where it got to
 * and carry on, so the offset bookkeeping is the contract: a chunk sent at the wrong offset must be
 * refused rather than written at the end, and the file must only be handed to the job once the last
 * byte has landed.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class TusUploadServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final int EXPIRY_HOURS = 24;
    private static final String OPERATOR = "op-1";

    @Mock
    private TusUploadRepository tusUploadRepository;

    @Mock
    private BulkLoadJobService bulkLoadJobService;

    @TempDir
    Path storageRoot;

    private TusUploadServiceImpl service;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        jobId = UUID.randomUUID();
        service = new TusUploadServiceImpl(
                tusUploadRepository,
                bulkLoadJobService,
                storageRoot.toString(),
                EXPIRY_HOURS,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TusUpload existing(long offset, long totalSize, boolean completed, Instant expiresAt) {
        TusUpload upload = new TusUpload();
        upload.setId(UUID.randomUUID());
        upload.setJobId(jobId);
        upload.setOperatorId(OPERATOR);
        upload.setFileName("parts.csv");
        upload.setTotalSize(totalSize);
        upload.setUploadOffset(offset);
        upload.setCompleted(completed);
        upload.setExpiresAt(expiresAt);
        return upload;
    }

    /** Registers the upload with the repository stub and gives it its temp file. */
    private TusUpload known(TusUpload upload) throws IOException {
        when(tusUploadRepository.findById(upload.getId())).thenReturn(Optional.of(upload));
        Files.createFile(storageRoot.resolve(".tus").resolve(upload.getId().toString()));
        return upload;
    }

    // ─── create ──────────────────────────────────────────────────────────────

    @Test
    void createUpload_savesTheUpload_andOpensItsTempFile() {
        when(tusUploadRepository.save(any(TusUpload.class))).thenAnswer(invocation -> {
            TusUpload saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        TusUploadService.Created created = service.createUpload(jobId, "parts.csv", 512L, OPERATOR);

        assertThat(created.expiresAt()).isEqualTo(NOW.plusSeconds(EXPIRY_HOURS * 3600L));
        assertThat(storageRoot.resolve(".tus").resolve(created.id().toString())).exists();
    }

    @Test
    void createUpload_stripsAnyPathFromTheClientsFileName() {
        // The name comes from the client, and it ends up joined to the storage root. Keeping the
        // last segment only is what stops "../../etc/passwd" naming a file outside the job's dir.
        when(tusUploadRepository.save(any(TusUpload.class))).thenAnswer(invocation -> {
            TusUpload saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        service.createUpload(jobId, "../../etc/pa$$wd", 8L, OPERATOR);

        verify(tusUploadRepository).save(org.mockito.ArgumentMatchers.argThat(upload -> {
            assertThat(upload.getFileName()).isEqualTo("pa__wd");
            return true;
        }));
    }

    // ─── info ────────────────────────────────────────────────────────────────

    @Test
    void getInfo_reportsWhereTheUploadGotTo() {
        TusUpload upload = existing(120L, 512L, false, NOW.plusSeconds(3600));
        when(tusUploadRepository.findById(upload.getId())).thenReturn(Optional.of(upload));

        TusUploadService.Info info = service.getInfo(upload.getId());

        assertThat(info.uploadOffset()).isEqualTo(120L);
        assertThat(info.totalSize()).isEqualTo(512L);
        assertThat(info.completed()).isFalse();
    }

    @Test
    void getInfo_anUnknownUploadIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(tusUploadRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInfo(unknown))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(unknown.toString());
    }

    // ─── append ──────────────────────────────────────────────────────────────

    @Test
    void appendChunk_writesTheChunk_andAdvancesTheOffset() throws IOException {
        TusUpload upload = known(existing(0L, 10L, false, NOW.plusSeconds(3600)));
        when(tusUploadRepository.advanceOffset(upload.getId(), 0L, 4L)).thenReturn(1);

        long offset = service.appendChunk(upload.getId(), 0L, stream("abcd"), 4L);

        assertThat(offset).isEqualTo(4L);
        assertThat(storageRoot.resolve(".tus").resolve(upload.getId().toString()))
                .hasContent("abcd");
        // Four of ten bytes: nothing is handed to the job until the file is whole.
        verify(bulkLoadJobService, never()).markUploadStored(any(), any(), any());
    }

    @Test
    void appendChunk_appendsRatherThanOverwriting() throws IOException {
        TusUpload upload = known(existing(0L, 16L, false, NOW.plusSeconds(3600)));
        when(tusUploadRepository.advanceOffset(eq(upload.getId()), anyLong(), anyLong()))
                .thenReturn(1);

        service.appendChunk(upload.getId(), 0L, stream("abcd"), 4L);
        upload.setUploadOffset(4L);
        service.appendChunk(upload.getId(), 4L, stream("efgh"), 4L);

        assertThat(storageRoot.resolve(".tus").resolve(upload.getId().toString()))
                .hasContent("abcdefgh");
    }

    @Test
    void appendChunk_refusesAChunkSentAtTheWrongOffset() throws IOException {
        // Writing it anyway would leave a file that is the right length and the wrong bytes.
        TusUpload upload = known(existing(4L, 16L, false, NOW.plusSeconds(3600)));

        assertThatThrownBy(() -> service.appendChunk(upload.getId(), 0L, stream("abcd"), 4L))
                .isInstanceOf(TusOffsetConflictException.class)
                .satisfies(thrown -> {
                    TusOffsetConflictException conflict = (TusOffsetConflictException) thrown;
                    assertThat(conflict.getExpected()).isZero();
                    assertThat(conflict.getActual()).isEqualTo(4L);
                });
    }

    @Test
    void appendChunk_refusesWhenAConcurrentWriterMovedTheOffsetFirst() throws IOException {
        // The read-then-write is not atomic; advanceOffset's compare-and-set is what makes it so,
        // and a zero row count means another request for the same upload won the race.
        TusUpload upload = known(existing(0L, 16L, false, NOW.plusSeconds(3600)));
        when(tusUploadRepository.advanceOffset(upload.getId(), 0L, 4L)).thenReturn(0);

        assertThatThrownBy(() -> service.appendChunk(upload.getId(), 0L, stream("abcd"), 4L))
                .isInstanceOf(TusOffsetConflictException.class);
        verify(bulkLoadJobService, never()).markUploadStored(any(), any(), any());
    }

    @Test
    void appendChunk_refusesAnAlreadyCompletedUpload() throws IOException {
        TusUpload upload = known(existing(16L, 16L, true, NOW.plusSeconds(3600)));

        assertThatThrownBy(() -> service.appendChunk(upload.getId(), 16L, stream("x"), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already complete");
    }

    @Test
    void appendChunk_refusesAnExpiredUpload() throws IOException {
        TusUpload upload = known(existing(0L, 16L, false, NOW.minusSeconds(1)));

        assertThatThrownBy(() -> service.appendChunk(upload.getId(), 0L, stream("abcd"), 4L))
                .isInstanceOf(TusUploadExpiredException.class);
    }

    @Test
    void appendChunk_movesTheFileIntoTheJobAndTellsTheJobOnTheLastByte() throws IOException {
        TusUpload upload = known(existing(0L, 4L, false, NOW.plusSeconds(3600)));
        when(tusUploadRepository.advanceOffset(upload.getId(), 0L, 4L)).thenReturn(1);

        long offset = service.appendChunk(upload.getId(), 0L, stream("abcd"), 4L);

        assertThat(offset).isEqualTo(4L);
        assertThat(storageRoot.resolve(jobId.toString()).resolve("parts.csv")).hasContent("abcd");
        assertThat(storageRoot.resolve(".tus").resolve(upload.getId().toString()))
                .doesNotExist();
        verify(bulkLoadJobService)
                .markUploadStored(
                        jobId, OPERATOR, Path.of(jobId.toString(), "parts.csv").toString());
        assertThat(upload.isCompleted()).isTrue();
    }

    // ─── delete and cleanup ──────────────────────────────────────────────────

    @Test
    void deleteUpload_removesTheRowAndTheTempFile() throws IOException {
        TusUpload upload = known(existing(4L, 16L, false, NOW.plusSeconds(3600)));

        service.deleteUpload(upload.getId());

        assertThat(storageRoot.resolve(".tus").resolve(upload.getId().toString()))
                .doesNotExist();
        verify(tusUploadRepository).delete(upload);
    }

    @Test
    void cleanupExpiredUploads_removesEveryExpiredUpload() throws IOException {
        TusUpload first = existing(4L, 16L, false, NOW.minusSeconds(60));
        TusUpload second = existing(8L, 16L, false, NOW.minusSeconds(120));
        Files.createFile(storageRoot.resolve(".tus").resolve(first.getId().toString()));
        Files.createFile(storageRoot.resolve(".tus").resolve(second.getId().toString()));
        when(tusUploadRepository.findByExpiresAtBeforeAndCompletedFalse(NOW)).thenReturn(List.of(first, second));

        service.cleanupExpiredUploads();

        assertThat(storageRoot.resolve(".tus").resolve(first.getId().toString()))
                .doesNotExist();
        verify(tusUploadRepository).delete(first);
        verify(tusUploadRepository).delete(second);
    }

    @Test
    void cleanupExpiredUploads_oneFailureDoesNotStrandTheRest() throws IOException {
        // A sweep that gave up on the first bad row would leave the temp directory growing until
        // someone noticed, which is exactly what the scheduled sweep exists to prevent.
        TusUpload bad = existing(4L, 16L, false, NOW.minusSeconds(60));
        TusUpload good = existing(8L, 16L, false, NOW.minusSeconds(120));
        Files.createFile(storageRoot.resolve(".tus").resolve(good.getId().toString()));
        when(tusUploadRepository.findByExpiresAtBeforeAndCompletedFalse(NOW)).thenReturn(List.of(bad, good));
        org.mockito.Mockito.doThrow(new IllegalStateException("row is locked"))
                .when(tusUploadRepository)
                .delete(bad);

        service.cleanupExpiredUploads();

        verify(tusUploadRepository).delete(good);
        assertThat(storageRoot.resolve(".tus").resolve(good.getId().toString())).doesNotExist();
    }

    @Test
    void cleanupExpiredUploads_doesNothingWhenNoneHaveExpired() {
        when(tusUploadRepository.findByExpiresAtBeforeAndCompletedFalse(NOW)).thenReturn(List.of());

        service.cleanupExpiredUploads();

        verify(tusUploadRepository, never()).delete(any(TusUpload.class));
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
