package com.positivity.image.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.image.internal.entity.ImageContentEntity;
import com.positivity.image.internal.entity.ImageEntity;
import com.positivity.image.internal.repository.ImageContentRepository;
import com.positivity.image.internal.repository.ImageRepository;
import com.positivity.image.service.model.StoredImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Storing image bytes without accumulating copies (CAP-324 #1257).
 *
 * <p>A catalogue refresh republishes unchanged manufacturer artwork on every cycle. The behaviour
 * that matters is therefore not "can it store an image" but what happens the second, tenth and
 * hundredth time it is handed the same one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImageStorageServiceImpl — storing without accumulating (#1257)")
class ImageStorageServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final byte[] ARTWORK = "tread-pattern-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String VENDOR_URI = "https://mkcat.example.com/images/9981.jpg";

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageContentRepository contentRepository;

    private ImageStorageServiceImpl storage;

    @BeforeEach
    void setUp() {
        storage = new ImageStorageServiceImpl(imageRepository, contentRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(imageRepository.save(any())).thenAnswer(invocation -> {
            ImageEntity saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(imageRepository.findFirstBySourceUriAndContentHash(any(), any())).thenReturn(Optional.empty());
        when(contentRepository.existsById(any())).thenReturn(false);
    }

    @Test
    @DisplayName("stores the bytes and reports having done so")
    void storesNewContent() {
        StoredImage stored = storage.store("tread.jpg", "image/jpeg", ARTWORK, VENDOR_URI, List.of("mkcat"));

        assertThat(stored.newlyStored()).isTrue();
        assertThat(stored.imageId()).isEqualTo(42L);
        assertThat(stored.byteSize()).isEqualTo(ARTWORK.length);
        assertThat(stored.contentHash()).hasSize(64);
        // Kept as evidence of where it came from, not as somewhere to fetch it from later.
        assertThat(stored.sourceUri()).isEqualTo(VENDOR_URI);
        verify(contentRepository).save(any());
    }

    @Test
    @DisplayName("re-ingesting the same artwork from the same origin returns the first reference")
    void reIngestDoesNotDuplicate() {
        ImageEntity alreadyHeld = new ImageEntity();
        alreadyHeld.setId(7L);
        alreadyHeld.setFilename("tread.jpg");
        alreadyHeld.setContentType("image/jpeg");
        alreadyHeld.setByteSize((long) ARTWORK.length);
        alreadyHeld.setContentHash(hashOf(ARTWORK));
        alreadyHeld.setSourceUri(VENDOR_URI);
        when(imageRepository.findFirstBySourceUriAndContentHash(VENDOR_URI, hashOf(ARTWORK)))
                .thenReturn(Optional.of(alreadyHeld));

        StoredImage stored = storage.store("tread.jpg", "image/jpeg", ARTWORK, VENDOR_URI, List.of("mkcat"));

        // The same id, so a consumer can tell "unchanged" from "new" and skip re-rendering.
        assertThat(stored.imageId()).isEqualTo(7L);
        assertThat(stored.newlyStored()).isFalse();
        verify(imageRepository, never()).save(any());
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("identical bytes already held are not written a second time")
    void identicalBytesAreStoredOnce() {
        // A different manufacturer publishing the same picture: a new record, but one copy of the
        // content. Content is addressed by its own hash precisely so this is free.
        when(contentRepository.existsById(hashOf(ARTWORK))).thenReturn(true);

        StoredImage stored = storage.store("other.jpg", "image/jpeg", ARTWORK, "https://elsewhere/x.jpg", List.of());

        assertThat(stored.newlyStored()).isTrue();
        verify(imageRepository).save(any());
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("losing the race to write content is success, not a failed ingest")
    void concurrentContentInsertIsNotAFailure() {
        // Two catalogue runs, or two instances, fetching the same artwork. The collision means the
        // content is present, which is the outcome wanted.
        when(contentRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        StoredImage stored = storage.store("tread.jpg", "image/jpeg", ARTWORK, VENDOR_URI, List.of());

        assertThat(stored.imageId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("an empty body is refused, because storing one would stop it being retried")
    void emptyContentIsRefused() {
        // An empty body is what a failed download looks like. Stored, it would satisfy every later
        // "do we already have this?" check and the artwork would silently never arrive.
        assertThatThrownBy(() -> storage.store("tread.jpg", "image/jpeg", new byte[0], VENDOR_URI, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retried");

        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("a null body is refused the same way an empty one is")
    void nullContentIsRefused() {
        assertThatThrownBy(() -> storage.store("tread.jpg", "image/jpeg", null, VENDOR_URI, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retried");

        verify(imageRepository, never()).save(any());
    }

    @Test
    @DisplayName("a re-ingested record missing its type and size still yields a usable reference")
    void referenceFillsInMissingTypeAndSize() {
        // Rows written before the content columns existed carry neither. The reference has to name
        // a content type and a size regardless, or a consumer cannot render what it is handed.
        ImageEntity legacy = new ImageEntity();
        legacy.setId(7L);
        legacy.setFilename("legacy.jpg");
        legacy.setContentHash("whatever-hash");
        legacy.setSourceUri(VENDOR_URI);
        legacy.setContentType(null);
        legacy.setByteSize(null);
        when(imageRepository.findFirstBySourceUriAndContentHash(any(), any())).thenReturn(Optional.of(legacy));

        StoredImage reference = storage.store("tread.jpg", "image/jpeg", ARTWORK, VENDOR_URI, List.of());

        assertThat(reference.contentType()).isEqualTo("application/octet-stream");
        assertThat(reference.byteSize()).isZero();
        assertThat(reference.newlyStored()).isFalse();
    }

    @Test
    @DisplayName("a direct upload with no origin is stored, and the origin lookup is skipped")
    void uploadWithoutAnOriginIsAccepted() {
        StoredImage stored = storage.store("hand-uploaded.png", "image/png", ARTWORK, null, null);

        assertThat(stored.sourceUri()).isNull();
        assertThat(stored.newlyStored()).isTrue();
        // No origin to match on, so the lookup is skipped rather than run with a null key.
        verify(imageRepository, never()).findFirstBySourceUriAndContentHash(any(), any());
    }

    @Test
    @DisplayName("the content hash is the SHA-256 of the bytes, so two callers agree on identity")
    void contentHashIsSha256() {
        ArgumentCaptor<ImageContentEntity> captor = ArgumentCaptor.forClass(ImageContentEntity.class);
        storage.store("tread.jpg", "image/jpeg", ARTWORK, VENDOR_URI, List.of());
        verify(contentRepository).save(captor.capture());

        assertThat(captor.getValue().getContentHash()).isEqualTo(hashOf(ARTWORK));
        assertThat(captor.getValue().getContent()).isEqualTo(ARTWORK);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    private static String hashOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
