package com.positivity.posimage.repository;

import com.positivity.posimage.model.ImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<ImageEntity, Long> {
    Optional<ImageEntity> findByFilename(String filename);
}
