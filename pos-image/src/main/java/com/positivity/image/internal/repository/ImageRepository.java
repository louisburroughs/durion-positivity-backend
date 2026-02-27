package com.positivity.image.internal.repository;

import com.positivity.image.internal.entity.ImageEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRepository extends JpaRepository<ImageEntity, Long> {
    Optional<ImageEntity> findByFilename(String filename);
}
