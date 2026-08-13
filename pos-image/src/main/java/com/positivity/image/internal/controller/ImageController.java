package com.positivity.image.internal.controller;

import com.positivity.image.internal.dto.ImageFileView;
import com.positivity.image.service.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Image API", description = "Operations related to image retrieval and serving")
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/images")
public class ImageController {
    private final ImageService imageService;

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
        Optional<ImageFileView> imageOpt = imageService.findByFilename(filename);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageFileView image = imageOpt.get();
        return serveImageFile(image);
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
