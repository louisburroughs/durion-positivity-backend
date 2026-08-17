package com.positivity.image.internal.service;

import com.positivity.image.internal.dto.ImageContentView;
import com.positivity.image.internal.dto.ImageFileView;
import com.positivity.image.internal.entity.ImageEntity;
import com.positivity.image.internal.repository.ImageContentRepository;
import com.positivity.image.internal.repository.ImageRepository;
import com.positivity.image.service.ImageService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ImageServiceImpl implements ImageService {
    private final ImageRepository imageRepository;
    private final ImageContentRepository contentRepository;

    public ImageServiceImpl(ImageRepository imageRepository, ImageContentRepository contentRepository) {
        this.imageRepository = imageRepository;
        this.contentRepository = contentRepository;
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

    @Override
    public Optional<ImageContentView> findContentById(Long id) {
        return imageRepository
                .findById(id)
                .flatMap(ImageServiceImpl::withContent)
                .map(this::toContentView);
    }

    @Override
    public Optional<ImageContentView> findContentByFilename(String filename) {
        return imageRepository
                .findByFilename(filename)
                .flatMap(ImageServiceImpl::withContent)
                .map(this::toContentView);
    }

    /** Records that predate content storage carry no hash and are served from their url path. */
    private static Optional<ImageEntity> withContent(ImageEntity image) {
        return image.getContentHash() == null || image.getContentHash().isBlank()
                ? Optional.empty()
                : Optional.of(image);
    }

    private ImageContentView toContentView(ImageEntity image) {
        return contentRepository
                .findById(image.getContentHash())
                .map(blob -> new ImageContentView(image.getFilename(), blob.getContentType(), blob.getContent()))
                // A record pointing at content that is not there is a broken row, not an empty
                // image: reporting it as absent sends the reader to the url fallback, which for a
                // stored image is a path that never existed.
                .orElseThrow(() -> new IllegalStateException("Image " + image.getId() + " references content "
                        + image.getContentHash() + " which is not stored"));
    }
}
