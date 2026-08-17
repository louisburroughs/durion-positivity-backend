package com.positivity.image.internal.repository;

import com.positivity.image.internal.entity.ImageContentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Image bytes, keyed by their own SHA-256 (CAP-324 #1257). */
@Repository
public interface ImageContentRepository extends JpaRepository<ImageContentEntity, String> {}
