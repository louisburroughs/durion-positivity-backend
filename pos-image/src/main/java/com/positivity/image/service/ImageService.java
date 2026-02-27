package com.positivity.image.service;

import com.positivity.image.internal.dto.ImageFileView;
import java.util.Optional;

public interface ImageService {
    Optional<ImageFileView> findById(Long id);

    Optional<ImageFileView> findByFilename(String filename);
}
