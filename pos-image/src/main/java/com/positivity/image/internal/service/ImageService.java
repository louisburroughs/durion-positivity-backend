package com.positivity.image.internal.service;

import com.positivity.image.internal.dto.ImageContentView;
import com.positivity.image.internal.dto.ImageFileView;
import java.util.Optional;

public interface ImageService {
    Optional<ImageFileView> findById(Long id);

    Optional<ImageFileView> findByFilename(String filename);

    /**
     * The stored bytes for an image, when this service holds them (CAP-324 #1257).
     *
     * <p>Empty for records that predate content storage — those name a file for the caller to
     * resolve, and are still served that way. A caller should try this first and fall back, rather
     * than choosing by inspecting the record: which of the two applies is this service's business.
     */
    Optional<ImageContentView> findContentById(Long id);

    /** The stored bytes for an image looked up by filename. */
    Optional<ImageContentView> findContentByFilename(String filename);
}
