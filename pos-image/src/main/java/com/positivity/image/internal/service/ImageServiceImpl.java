package com.positivity.image.internal.service;

import com.positivity.image.internal.dto.ImageFileView;
import com.positivity.image.internal.repository.ImageRepository;
import com.positivity.image.service.ImageService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ImageServiceImpl implements ImageService {
    private final ImageRepository imageRepository;

    public ImageServiceImpl(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @Override
    public Optional<ImageFileView> findById(Long id) {
        return imageRepository.findById(id).map(image -> new ImageFileView(image.getFilename(), image.getUrl()));
    }

    @Override
    public Optional<ImageFileView> findByFilename(String filename) {
        return imageRepository
                .findByFilename(filename)
                .map(image -> new ImageFileView(image.getFilename(), image.getUrl()));
    }
}
