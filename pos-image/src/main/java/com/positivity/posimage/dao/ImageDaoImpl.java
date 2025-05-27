package com.positivity.posimage.dao;

import com.positivity.posimage.model.ImageEntity;
import com.positivity.posimage.repository.ImageRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class ImageDaoImpl implements ImageDao {
    private final ImageRepository imageRepository;

    public ImageDaoImpl(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @Override
    public Optional<ImageEntity> findById(Long id) {
        return imageRepository.findById(id);
    }

    @Override
    public Optional<ImageEntity> findByFilename(String filename) {
        return imageRepository.findByFilename(filename);
    }
}
