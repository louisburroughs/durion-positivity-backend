package com.positivity.posimage.dao;

import com.positivity.posimage.model.ImageEntity;
import java.util.Optional;

public interface ImageDao {
    Optional<ImageEntity> findById(Long id);
    Optional<ImageEntity> findByFilename(String filename);
}
