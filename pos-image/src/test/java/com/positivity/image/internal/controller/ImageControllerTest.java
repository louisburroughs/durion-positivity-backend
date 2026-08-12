package com.positivity.image.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.image.internal.dto.ImageFileView;
import com.positivity.image.service.ImageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * First tests for {@link ImageController} — this module was at 0%.
 *
 * <p>
 * The controller serves bytes off the filesystem for a row held in the database,
 * so it has two independent ways to miss: no row, or a row whose file is gone.
 * Both must be a 404. The second is the one worth a test — a database row
 * pointing at a deleted or unmounted file is the normal failure after a restore,
 * a volume remount, or a partial cleanup, and without the existence check it
 * surfaces as a 500 (or a stream that fails mid-response) instead of an honest
 * "not found".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImageController — serving stored image files")
class ImageControllerTest {

    @Mock
    private ImageService imageService;

    @Test
    @DisplayName("serves a stored file as an attachment under its own filename")
    void servesExistingFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("vehicle-front.jpg");
        Files.writeString(file, "image-bytes");
        when(imageService.findById(1L))
                .thenReturn(Optional.of(new ImageFileView("vehicle-front.jpg", file.toString())));

        ResponseEntity<Resource> response = new ImageController(imageService).getImageById(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        // The stored filename, not the id, is what the browser saves it as.
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("vehicle-front.jpg");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
    }

    @Test
    @DisplayName("404s when no row exists for the id")
    void missingRowIsNotFound() {
        when(imageService.findById(99L)).thenReturn(Optional.empty());

        assertThat(new ImageController(imageService).getImageById(99L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("404s when the row exists but its file is gone from disk")
    void missingFileIsNotFound(@TempDir Path tempDir) {
        // Row survives, file does not: the state after a restore, a remount, or a partial
        // cleanup. Without the existence check this is a 500 or a broken stream.
        when(imageService.findById(1L))
                .thenReturn(Optional.of(new ImageFileView(
                        "gone.jpg", tempDir.resolve("gone.jpg").toString())));

        assertThat(new ImageController(imageService).getImageById(1L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("applies the same two rules to filename lookups")
    void filenameLookupBehavesIdentically(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("logo.png");
        Files.writeString(file, "png-bytes");
        when(imageService.findByFilename("logo.png"))
                .thenReturn(Optional.of(new ImageFileView("logo.png", file.toString())));
        when(imageService.findByFilename("absent.png")).thenReturn(Optional.empty());

        ImageController controller = new ImageController(imageService);

        assertThat(controller.getImageByFilename("logo.png").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.getImageByFilename("absent.png").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
