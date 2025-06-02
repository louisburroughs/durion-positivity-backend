package com.positivity.posimage.controller;

import com.positivity.posimage.dao.ImageDao;
import com.positivity.posimage.model.ImageEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Slf4j
@Tag(name = "Image API", description = "Operations related to image retrieval and serving")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/images")
public class ImageController {
    private final ImageDao imageDao;

    @Operation(summary = "Get image by ID", description = "Retrieve an image file by its unique database ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image file returned successfully."),
            @ApiResponse(responseCode = "404", description = "Image not found.")
    })
    @GetMapping("/id/{id}")
    public ResponseEntity<Resource> getImageById(
            @Parameter(description = "ID of the image to retrieve", example = "1")
            @PathVariable Long id) {
        Optional<ImageEntity> imageOpt = imageDao.findById(id);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageEntity image = imageOpt.get();
        return serveImageFile(image);
    }

    @Operation(summary = "Get image by filename", description = "Retrieve an image file by its filename.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image file returned successfully."),
            @ApiResponse(responseCode = "404", description = "Image not found.")
    })
    @GetMapping("/filename/{filename}")
    public ResponseEntity<Resource> getImageByFilename(
            @Parameter(description = "Filename of the image to retrieve", example = "logo.png")
            @PathVariable String filename) {
        Optional<ImageEntity> imageOpt = imageDao.findByFilename(filename);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageEntity image = imageOpt.get();
        return serveImageFile(image);
    }

    private ResponseEntity<Resource> serveImageFile(ImageEntity image) {
        FileSystemResource fileResource = new FileSystemResource(image.getUrl());
        if (!fileResource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + image.getFilename())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fileResource);
    }
}
