package com.positivity.image.internal.controller;

import com.positivity.image.internal.dto.ImageContentView;
import com.positivity.image.internal.dto.ImageFileView;
import com.positivity.image.internal.dto.StoreImageRequest;
import com.positivity.image.internal.security.ImagePermissions;
import com.positivity.image.internal.service.ImageService;
import com.positivity.image.internal.service.ImageStorageService;
import com.positivity.image.internal.service.model.StoredImage;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Image API", description = "Operations related to image retrieval and serving")
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/images")
public class ImageController {
    private final ImageService imageService;
    private final ImageStorageService imageStorageService;

    @Operation(
            operationId = "getImageById",
            summary = "Get image by ID",
            description = """
                    Returns the stored image file for a numeric image id as a binary attachment.
                    Use this tool when the image's database id is already known; use getImageByFilename instead when
                    only the filename is known.
                    Preconditions: the image record must exist and its backing file must still be present on disk.
                    Required inputs: id (numeric database id) path parameter; there is no request body and no
                    resizing or format options.
                    No events are emitted and no state changes; this is a read-only file retrieval.
                    Returns 404 when no image record has that id or the backing file is missing from storage.
                    """,
            tags = {"Image API"})
    @ApiResponse(responseCode = "200", description = "Image file returned successfully.")
    @ApiResponse(responseCode = "404", description = "Image not found.")
    @GetMapping("/id/{id}")
    public ResponseEntity<Resource> getImageById(
            @Parameter(description = "ID of the image to retrieve", example = "1") @PathVariable Long id) {
        Optional<ImageContentView> stored = imageService.findContentById(id);
        if (stored.isPresent()) {
            return serveStoredContent(stored.get());
        }
        Optional<ImageFileView> imageOpt = imageService.findById(id);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageFileView image = imageOpt.get();
        return serveImageFile(image);
    }

    @Operation(
            operationId = "getImageByFilename",
            summary = "Get image by filename",
            description = """
                    Returns the stored image file matching a filename as a binary attachment.
                    Use this tool when only the filename is known, such as a reference embedded in catalog data; use
                    getImageById instead when the numeric id is available, because filenames are not guaranteed
                    unique over time.
                    Preconditions: an image record with that exact filename must exist and its backing file must
                    still be present on disk.
                    Required inputs: filename path parameter, matched exactly including extension; there is no
                    request body and no resizing or format options.
                    No events are emitted and no state changes; this is a read-only file retrieval.
                    Returns 404 when no image record matches the filename or the backing file is missing from
                    storage.
                    """,
            tags = {"Image API"})
    @ApiResponse(responseCode = "200", description = "Image file returned successfully.")
    @ApiResponse(responseCode = "404", description = "Image not found.")
    @GetMapping("/filename/{filename}")
    public ResponseEntity<Resource> getImageByFilename(
            @Parameter(description = "Filename of the image to retrieve", example = "logo.png") @PathVariable
                    String filename) {
        Optional<ImageContentView> stored = imageService.findContentByFilename(filename);
        if (stored.isPresent()) {
            return serveStoredContent(stored.get());
        }
        Optional<ImageFileView> imageOpt = imageService.findByFilename(filename);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageFileView image = imageOpt.get();
        return serveImageFile(image);
    }

    /**
     * Serves bytes this service holds.
     *
     * <p>Inline rather than as an attachment, and with the real content type: these are product
     * images rendered in a page, not files a user is being asked to download. The legacy path below
     * keeps its attachment disposition, because changing how existing callers receive existing
     * images is not this story's business.
     */
    private ResponseEntity<Resource> serveStoredContent(ImageContentView image) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.filename() + "\"")
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(new ByteArrayResource(image.content()));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"image:image:store"})
    @PreAuthorize("hasAuthority('" + ImagePermissions.IMAGE_STORE + "')")
    @Operation(
            operationId = "storeImage",
            summary = "Store An Image",
            description = """
                    Stores image bytes and returns a reference to them, or returns the reference already held when \
                    the same bytes from the same origin have been stored before.
                    Use this tool when ingesting artwork that must not depend on a third party to render, such as \
                    manufacturer marketing images; do not use it to register an image that already lives \
                    somewhere this service can read, because that needs no copy.
                    Preconditions: the content must not be empty, because an empty body is a failed download and \
                    storing one would stop it ever being retried.
                    Required inputs: filename, contentType and content as base64; sourceUri and context are \
                    optional, and sourceUri is kept as provenance rather than as a render source.
                    No events are emitted.
                    Returns 200 with the reference, whose newlyStored flag says whether this call did the storing \
                    or found the bytes already held, and 400 when the content is empty.
                    """,
            tags = {"Image API"})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The image to store, with the origin it was fetched from where there was one.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = StoreImageRequest.class),
                            examples =
                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                            name = "Manufacturer tread pattern artwork",
                                            value = """
                                                    {
                                                      "filename": "primacy-4-tread.jpg",
                                                      "contentType": "image/jpeg",
                                                      "content": "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAY=",
                                                      "sourceUri": "https://mkcat.example.com/images/9981.jpg",
                                                      "context": ["mkcat", "tread-design"]
                                                    }
                                                    """)))
    @ApiResponse(responseCode = "200", description = "The stored image reference.")
    @ApiResponse(
            responseCode = "400",
            description = "The content was empty, or was not valid base64.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<StoredImage> storeImage(@Valid @RequestBody StoreImageRequest request) {
        return ResponseEntity.ok(imageStorageService.store(
                request.filename(),
                request.contentType(),
                request.decodedContent(),
                request.sourceUri(),
                request.context()));
    }

    private ResponseEntity<Resource> serveImageFile(ImageFileView image) {
        FileSystemResource fileResource = new FileSystemResource(image.url());
        if (!fileResource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + image.filename())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fileResource);
    }
}
