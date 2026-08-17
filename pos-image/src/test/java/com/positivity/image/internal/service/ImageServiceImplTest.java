package com.positivity.image.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.image.internal.dto.ImageContentView;
import com.positivity.image.internal.dto.ImageFileView;
import com.positivity.image.internal.entity.ImageContentEntity;
import com.positivity.image.internal.entity.ImageEntity;
import com.positivity.image.internal.repository.ImageContentRepository;
import com.positivity.image.internal.repository.ImageRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Reading images back (CAP-324 #1257). A record that carries a content hash is served from stored
 * bytes; one that predates content storage carries no hash and falls back to its url path. A record
 * whose hash points at content that is not there is a broken row, not an empty image.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ImageServiceImpl")
class ImageServiceImplTest {

    private static final long IMAGE_ID = 42L;
    private static final String FILENAME = "logo.png";
    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final byte[] BYTES = "png-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageContentRepository contentRepository;

    private ImageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ImageServiceImpl(imageRepository, contentRepository);
    }

    private static ImageEntity record(String contentHash) {
        ImageEntity image = new ImageEntity();
        image.setId(IMAGE_ID);
        image.setFilename(FILENAME);
        image.setUrl("/legacy/path/logo.png");
        image.setContentHash(contentHash);
        return image;
    }

    private static ImageContentEntity blob() {
        ImageContentEntity content = new ImageContentEntity();
        content.setContentHash(HASH);
        content.setContentType("image/png");
        content.setByteSize(BYTES.length);
        content.setContent(BYTES);
        return content;
    }

    @Nested
    @DisplayName("file lookups")
    class FileLookups {

        @Test
        @DisplayName("maps a record to its filename and url by id")
        void findsById() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(record(HASH)));

            assertThat(service.findById(IMAGE_ID)).contains(new ImageFileView(FILENAME, "/legacy/path/logo.png"));
        }

        @Test
        @DisplayName("maps a record to its filename and url by filename")
        void findsByFilename() {
            when(imageRepository.findByFilename(FILENAME)).thenReturn(Optional.of(record(HASH)));

            assertThat(service.findByFilename(FILENAME)).contains(new ImageFileView(FILENAME, "/legacy/path/logo.png"));
        }

        @Test
        @DisplayName("returns empty for an unknown id or filename")
        void missingRecord() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.empty());
            when(imageRepository.findByFilename(FILENAME)).thenReturn(Optional.empty());

            assertThat(service.findById(IMAGE_ID)).isEmpty();
            assertThat(service.findByFilename(FILENAME)).isEmpty();
        }
    }

    @Nested
    @DisplayName("content lookups")
    class ContentLookups {

        @Test
        @DisplayName("serves the stored bytes for a record that carries a hash")
        void servesStoredContent() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(record(HASH)));
            when(contentRepository.findById(HASH)).thenReturn(Optional.of(blob()));

            assertThat(service.findContentById(IMAGE_ID)).contains(new ImageContentView(FILENAME, "image/png", BYTES));
        }

        @Test
        @DisplayName("serves the stored bytes when looked up by filename")
        void servesStoredContentByFilename() {
            when(imageRepository.findByFilename(FILENAME)).thenReturn(Optional.of(record(HASH)));
            when(contentRepository.findById(HASH)).thenReturn(Optional.of(blob()));

            assertThat(service.findContentByFilename(FILENAME))
                    .contains(new ImageContentView(FILENAME, "image/png", BYTES));
        }

        @Test
        @DisplayName("reports no content for a record that predates content storage")
        void nullHashFallsBackToUrl() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(record(null)));
            when(imageRepository.findByFilename(FILENAME)).thenReturn(Optional.of(record(null)));

            assertThat(service.findContentById(IMAGE_ID)).isEmpty();
            assertThat(service.findContentByFilename(FILENAME)).isEmpty();
        }

        @Test
        @DisplayName("treats a blank hash the same as none")
        void blankHashFallsBackToUrl() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(record("   ")));

            assertThat(service.findContentById(IMAGE_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for an unknown record rather than reaching for content")
        void missingRecord() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.empty());
            when(imageRepository.findByFilename(FILENAME)).thenReturn(Optional.empty());

            assertThat(service.findContentById(IMAGE_ID)).isEmpty();
            assertThat(service.findContentByFilename(FILENAME)).isEmpty();
        }

        @Test
        @DisplayName("fails loudly when a record points at content that is not stored")
        void danglingContentHashIsABrokenRow() {
            when(imageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(record(HASH)));
            when(imageRepository.findByFilename(FILENAME)).thenReturn(Optional.of(record(HASH)));
            when(contentRepository.findById(HASH)).thenReturn(Optional.empty());

            // Reporting this as absent would send the reader to the url fallback, which for a
            // stored image is a path that never existed.
            assertThatThrownBy(() -> service.findContentById(IMAGE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("which is not stored");
            assertThatThrownBy(() -> service.findContentByFilename(FILENAME))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("which is not stored");
        }
    }
}
