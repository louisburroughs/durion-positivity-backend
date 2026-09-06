package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.TreadDesignCandidateDto;
import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.dto.TreadDesignImageDto;
import com.positivity.catalog.internal.dto.TreadDesignResolveRequest;
import com.positivity.catalog.internal.dto.TreadDesignTextDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.entity.TreadDesignImageEntity;
import com.positivity.catalog.internal.entity.TreadDesignMatchCandidateEntity;
import com.positivity.catalog.internal.entity.TreadDesignTextEntity;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.catalog.internal.enums.TreadDesignSource;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.TreadDesignImageRepository;
import com.positivity.catalog.internal.repository.TreadDesignMatchCandidateRepository;
import com.positivity.catalog.internal.repository.TreadDesignRepository;
import com.positivity.catalog.internal.repository.TreadDesignTextRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TreadDesignServiceImpl implements TreadDesignService {

    private final Clock clock;
    private final ProductRepository productRepository;
    private final TreadDesignRepository treadDesignRepository;
    private final TreadDesignTextRepository treadDesignTextRepository;
    private final TreadDesignImageRepository treadDesignImageRepository;
    private final TreadDesignMatchCandidateRepository treadDesignMatchCandidateRepository;

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
                        treadDesignImageRepository.findByTreadDesignId(design.getId()),
                        // The product read answers "what did the vendor say about this product",
                        // not "what else might this design have been" — candidates would be noise.
                        List.of()));
    }

    @Override
    @NonNull
    public Page<TreadDesignDto> findForReview(
            @NonNull Collection<TreadDesignMatchState> states,
            @Nullable UUID vendorProfileId,
            @NonNull Pageable pageable) {
        Page<TreadDesignEntity> page = treadDesignRepository.findForReview(states, vendorProfileId, pageable);
        List<UUID> designIds =
                page.getContent().stream().map(TreadDesignEntity::getId).toList();
        if (designIds.isEmpty()) {
            return page.map(design -> toDto(design, List.of(), List.of(), List.of()));
        }

        // Three queries for the whole page rather than three per design (CAP-324 #1352 review): a
        // review list defaults to size=50 and is capped at 200, so a naive per-design lookup here
        // would be the one query on this endpoint guaranteed to get slower as the catalog grows.
        var textsByDesign = treadDesignTextRepository.findByTreadDesignIdIn(designIds).stream()
                .collect(Collectors.groupingBy(TreadDesignTextEntity::getTreadDesignId));
        var imagesByDesign = treadDesignImageRepository.findByTreadDesignIdIn(designIds).stream()
                .collect(Collectors.groupingBy(TreadDesignImageEntity::getTreadDesignId));
        var candidatesByDesign =
                treadDesignMatchCandidateRepository.findByTreadDesignIdInOrderByScoreDesc(designIds).stream()
                        .collect(Collectors.groupingBy(TreadDesignMatchCandidateEntity::getTreadDesignId));

        return page.map(design -> toDto(
                design,
                textsByDesign.getOrDefault(design.getId(), List.of()),
                imagesByDesign.getOrDefault(design.getId(), List.of()),
                candidatesByDesign.getOrDefault(design.getId(), List.of())));
    }

    @Override
    @NonNull
    public List<TreadDesignCandidateDto> findCandidates(@NonNull UUID treadDesignId) {
        requireDesign(treadDesignId);
        return treadDesignMatchCandidateRepository.findByTreadDesignIdOrderByScoreDesc(treadDesignId).stream()
                .map(TreadDesignServiceImpl::toCandidateDto)
                .toList();
    }

    @Override
    @NonNull
    @Transactional
    public TreadDesignDto resolve(
            @NonNull UUID treadDesignId, @NonNull TreadDesignResolveRequest request, @NonNull String resolvedBy) {
        TreadDesignEntity design = requireDesign(treadDesignId);
        List<UUID> productIds = request.productIds() != null ? request.productIds() : List.of();

        TreadDesignMatchState newState =
                switch (request.action()) {
                    case ATTACH -> {
                        if (productIds.isEmpty()) {
                            throw new CatalogValidationException("ATTACH requires at least one productId");
                        }
                        if (request.deferUntil() != null) {
                            throw new CatalogValidationException("deferUntil applies to DEFER only");
                        }
                        attach(design, productIds);
                        // A design that has just been decided is no longer waiting for a date.
                        design.setDeferUntil(null);
                        yield TreadDesignMatchState.MATCHED;
                    }
                    case REJECT -> {
                        rejectExtraFields(request, productIds);
                        design.setDeferUntil(null);
                        // Deliberately detaches nothing: a reviewer rejecting the matcher's
                        // suggestions has said nothing about an attachment a person made earlier.
                        yield TreadDesignMatchState.REJECTED;
                    }
                    case DEFER -> {
                        if (!productIds.isEmpty()) {
                            throw new CatalogValidationException("productIds apply to ATTACH only");
                        }
                        design.setDeferUntil(request.deferUntil());
                        yield TreadDesignMatchState.DEFERRED;
                    }
                };

        design.setMatchState(newState);
        design.setMatchStateAt(Instant.now(clock));
        design.setResolvedBy(resolvedBy);
        design.setResolutionNote(request.note());
        TreadDesignEntity saved = treadDesignRepository.save(design);

        return toDto(
                saved,
                treadDesignTextRepository.findByTreadDesignId(saved.getId()),
                treadDesignImageRepository.findByTreadDesignId(saved.getId()),
                treadDesignMatchCandidateRepository.findByTreadDesignIdOrderByScoreDesc(saved.getId()));
    }

    private static void rejectExtraFields(TreadDesignResolveRequest request, List<UUID> productIds) {
        if (!productIds.isEmpty()) {
            throw new CatalogValidationException("productIds apply to ATTACH only");
        }
        if (request.deferUntil() != null) {
            throw new CatalogValidationException("deferUntil applies to DEFER only");
        }
    }

    /**
     * Points each named product at this design, by hand.
     *
     * <p>A product another design already holds <em>by a reviewer's decision</em> is a conflict, not
     * something to overwrite: two people have said incompatible things and the second one needs to
     * know. An automatic attachment, by contrast, is exactly what a reviewer is entitled to correct,
     * so it is replaced without ceremony.
     */
    private void attach(TreadDesignEntity design, List<UUID> productIds) {
        for (UUID productId : productIds) {
            ProductEntity product = productRepository
                    .findById(productId)
                    .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + productId));
            if (TreadDesignSource.MANUAL == product.getTreadDesignSource()
                    && product.getTreadDesignId() != null
                    && !product.getTreadDesignId().equals(design.getId())) {
                throw new CatalogBusinessRuleException("Product " + productId
                        + " is already manually attached to tread design " + product.getTreadDesignId());
            }
            product.setTreadDesignId(design.getId());
            product.setTreadDesignSource(TreadDesignSource.MANUAL);
            productRepository.save(product);
        }
    }

    private TreadDesignEntity requireDesign(UUID treadDesignId) {
        return treadDesignRepository
                .findById(treadDesignId)
                .orElseThrow(() -> new CatalogNotFoundException("Tread design not found: " + treadDesignId));
    }

    private TreadDesignDto toDto(
            TreadDesignEntity design,
            List<TreadDesignTextEntity> texts,
            List<TreadDesignImageEntity> images,
            List<TreadDesignMatchCandidateEntity> candidates) {
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
                texts.stream().map(TreadDesignServiceImpl::toTextDto).toList(),
                images.stream().map(TreadDesignServiceImpl::toImageDto).toList(),
                design.getMatchState(),
                design.getMatchStateAt(),
                design.getResolvedBy(),
                design.getResolutionNote(),
                design.getDeferUntil(),
                candidates.stream().map(TreadDesignServiceImpl::toCandidateDto).toList(),
                design.getUpdatedAt());
    }

    private static TreadDesignTextDto toTextDto(TreadDesignTextEntity text) {
        return new TreadDesignTextDto(
                text.getLanguageCode(), text.getName(), text.getDescription(), text.getFootNotes());
    }

    private static TreadDesignImageDto toImageDto(TreadDesignImageEntity image) {
        return new TreadDesignImageDto(image.getImageType(), image.getImageId(), image.isUnresolved());
    }

    private static TreadDesignCandidateDto toCandidateDto(TreadDesignMatchCandidateEntity candidate) {
        return new TreadDesignCandidateDto(candidate.getProductId(), candidate.getScore(), candidate.getTier());
    }
}
