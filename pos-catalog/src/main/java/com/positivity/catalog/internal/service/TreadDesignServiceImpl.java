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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
                .map(design -> toDto(
                        design,
                        treadDesignTextRepository.findByTreadDesignId(design.getId()),
                        treadDesignImageRepository.findByTreadDesignId(design.getId())));
    }

    @Override
    @NonNull
    public Page<TreadDesignDto> findUnmatched(@NonNull Pageable pageable) {
        Page<TreadDesignEntity> page = treadDesignRepository.findUnmatched(pageable);
        List<UUID> designIds =
                page.getContent().stream().map(TreadDesignEntity::getId).toList();
        if (designIds.isEmpty()) {
            return page.map(design -> toDto(design, List.of(), List.of()));
        }

        // Two queries for the whole page rather than two per design (CAP-324 #1352 review): a
        // review list defaults to size=50 and is capped at 200, so a naive per-design lookup here
        // would be the one query on this endpoint guaranteed to get slower as the catalog grows.
        var textsByDesign = treadDesignTextRepository.findByTreadDesignIdIn(designIds).stream()
                .collect(Collectors.groupingBy(TreadDesignTextEntity::getTreadDesignId));
        var imagesByDesign = treadDesignImageRepository.findByTreadDesignIdIn(designIds).stream()
                .collect(Collectors.groupingBy(TreadDesignImageEntity::getTreadDesignId));

        return page.map(design -> toDto(
                design,
                textsByDesign.getOrDefault(design.getId(), List.of()),
                imagesByDesign.getOrDefault(design.getId(), List.of())));
    }

    private TreadDesignDto toDto(
            TreadDesignEntity design, List<TreadDesignTextEntity> texts, List<TreadDesignImageEntity> images) {
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
                texts.stream().map(this::toTextDto).toList(),
                images.stream().map(this::toImageDto).toList(),
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
