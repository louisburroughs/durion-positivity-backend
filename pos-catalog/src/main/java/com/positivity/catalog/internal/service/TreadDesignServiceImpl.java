package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.dto.TreadDesignImageDto;
import com.positivity.catalog.internal.dto.TreadDesignTextDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import com.positivity.catalog.service.TreadDesignService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TreadDesignServiceImpl implements TreadDesignService {

    private final ProductRepository productRepository;
    private final TreadDesignRepository treadDesignRepository;
    private final TreadDesignTextRepository treadDesignTextRepository;
    private final TreadDesignImageRepository treadDesignImageRepository;

    @Override
    @NonNull
    public Optional<TreadDesignDto> findForProduct(@NonNull UUID productId) {
        return productRepository
                .findById(productId)
                .map(ProductEntity::getTreadDesignId)
                .flatMap(treadDesignRepository::findById)
                .map(this::toDto);
    }

    @Override
    @NonNull
    public Page<TreadDesignDto> findUnmatched(@NonNull Pageable pageable) {
        return treadDesignRepository.findUnmatched(pageable).map(this::toDto);
    }

    private TreadDesignDto toDto(TreadDesignEntity design) {
        return new TreadDesignDto(
                design.getId(),
                design.getVendorProfileId(),
                design.getSupplierRef(),
                design.getVendorVariantId(),
                design.getBrand(),
                design.getTreadDesign(),
                design.getTreadDesign2(),
                design.getProductName(),
                design.getVehicleType(),
                design.getSeasonality(),
                design.isHasUnresolvedImages(),
                treadDesignTextRepository.findByTreadDesignId(design.getId()).stream()
                        .map(this::toTextDto)
                        .toList(),
                treadDesignImageRepository.findByTreadDesignId(design.getId()).stream()
                        .map(this::toImageDto)
                        .toList(),
                design.getUpdatedAt());
    }

    private TreadDesignTextDto toTextDto(TreadDesignTextEntity text) {
        return new TreadDesignTextDto(
                text.getLanguageCode(), text.getName(), text.getDescription(), text.getFootNotes());
    }

    private TreadDesignImageDto toImageDto(TreadDesignImageEntity image) {
        return new TreadDesignImageDto(image.getImageType(), image.getImageId(), image.isUnresolved());
    }
}
