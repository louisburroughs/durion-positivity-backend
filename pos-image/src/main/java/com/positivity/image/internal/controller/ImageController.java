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
            summary = "Get image by ID",
            description = "Retrieve an image file by its unique database ID.",
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
            summary = "Get image by filename",
            description = "Retrieve an image file by its filename.",
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
