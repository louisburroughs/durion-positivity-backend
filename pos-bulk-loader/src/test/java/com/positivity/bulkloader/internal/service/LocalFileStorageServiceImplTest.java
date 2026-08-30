package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local-disk backing for uploaded files.
 *
 * <p>Everything here turns a job id and a client-supplied file name into a path under the storage
 * root, so the tests are mostly about that name: it arrives from outside and must not be able to
 * point at anything but the job's own directory.
 */
@SuppressWarnings("java:S100")
class LocalFileStorageServiceImplTest {

    @TempDir
    Path storageRoot;

    private LocalFileStorageServiceImpl service;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        jobId = UUID.randomUUID();
        service = new LocalFileStorageServiceImpl(storageRoot.toString());
    }

    @Test
    void store_writesTheFileUnderTheJobsOwnDirectory() {
        String storagePath = service.store(jobId, "parts.csv", stream("sku,qty\n"), 8L);

        assertThat(storagePath).isEqualTo(Path.of(jobId.toString(), "parts.csv").toString());
        assertThat(storageRoot.resolve(storagePath)).hasContent("sku,qty\n");
    }

    @Test
    void store_keepsOnlyTheLastSegmentOfAPathyFileName() {
        // The name is whatever the client sent. Without the strip, "../" segments would resolve
        // out of the job directory and let one upload overwrite another job's file.
        String storagePath = service.store(jobId, "../../etc/passwd", stream("x"), 1L);

        assertThat(storagePath).isEqualTo(Path.of(jobId.toString(), "passwd").toString());
        assertThat(storageRoot.resolve(jobId.toString()).resolve("passwd")).exists();
    }

    @Test
    void store_replacesTheCharactersThatAreNotSafeInAFileName() {
        String storagePath = service.store(jobId, "Q1 parts (final).csv", stream("x"), 1L);

        assertThat(storagePath).endsWith("Q1_parts__final_.csv");
    }

    @Test
    void store_replacesAFileAlreadyStoredUnderTheSameName() {
        // Re-uploading after a failed load is the normal way out of a bad file; refusing the
        // second write would leave the job pointing at the bad one.
        service.store(jobId, "parts.csv", stream("first"), 5L);

        service.store(jobId, "parts.csv", stream("second"), 6L);

        assertThat(storageRoot.resolve(jobId.toString()).resolve("parts.csv")).hasContent("second");
    }

    @Test
    void retrieve_readsBackWhatWasStored() throws IOException {
        String storagePath = service.store(jobId, "parts.csv", stream("sku,qty\n"), 8L);

        try (InputStream in = service.retrieve(storagePath)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("sku,qty\n");
        }
    }

    @Test
    void retrieve_aMissingFileIsAnError() {
        assertThatThrownBy(() -> service.retrieve(jobId + "/absent.csv"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("absent.csv");
    }

    @Test
    void delete_removesTheFile_andIsQuietWhenItIsAlreadyGone() {
        String storagePath = service.store(jobId, "parts.csv", stream("x"), 1L);

        service.delete(storagePath);

        assertThat(storageRoot.resolve(storagePath)).doesNotExist();
        assertThatCode(() -> service.delete(storagePath)).doesNotThrowAnyException();
    }

    @Test
    void constructor_createsTheStorageRootWhenItIsNotThereYet() {
        Path root = storageRoot.resolve("nested/root");

        new LocalFileStorageServiceImpl(root.toString());

        assertThat(root).isDirectory();
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
