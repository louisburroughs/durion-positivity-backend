package com.positivity.posimage.controller;

import com.positivity.posimage.dao.ImageDao;
import com.positivity.posimage.model.ImageEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

@RestController
@RequestMapping("/api/images")
public class ImageController {
    private final ImageDao imageDao;

    public ImageController(ImageDao imageDao) {
        this.imageDao = imageDao;
    }

    @GetMapping("/id/{id}")
    public ResponseEntity<Resource> getImageById(@PathVariable Long id) {
        Optional<ImageEntity> imageOpt = imageDao.findById(id);
        if (imageOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageEntity image = imageOpt.get();
        return serveImageFile(image);
    }

    @GetMapping("/filename/{filename}")
    public ResponseEntity<Resource> getImageByFilename(@PathVariable String filename) {
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
