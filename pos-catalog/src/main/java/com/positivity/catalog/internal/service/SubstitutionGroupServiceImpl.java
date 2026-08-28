package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.SubstitutionGroupCreateRequestDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupMemberRequestDto;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.SubstitutionGroupEntity;
import com.positivity.catalog.internal.entity.SubstitutionGroupMemberEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.SubstitutionGroupMemberRepository;
import com.positivity.catalog.internal.repository.SubstitutionGroupRepository;
import com.positivity.domainevents.AggregateTouch;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubstitutionGroupServiceImpl implements SubstitutionGroupService {

    private final SubstitutionGroupRepository groupRepository;
    private final SubstitutionGroupMemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final CatalogFactPublisher catalogFactPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public SubstitutionGroupDto createGroup(@NonNull SubstitutionGroupCreateRequestDto request) {
        SubstitutionGroupEntity entity = new SubstitutionGroupEntity();
        entity.setName(request.getName().trim());
        entity.setNotes(request.getNotes());
        return toDto(groupRepository.save(entity), List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public SubstitutionGroupDto getGroup(@NonNull UUID groupId) {
        SubstitutionGroupEntity group = findGroup(groupId);
        return toDto(group, membersOf(groupId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubstitutionGroupDto> listGroups() {
        return groupRepository.findAll().stream()
                .map(group -> toDto(group, membersOf(group.getId())))
                .toList();
    }

    @Override
    @Transactional
    public void deleteGroup(@NonNull UUID groupId) {
        SubstitutionGroupEntity group = findGroup(groupId);
        List<SubstitutionGroupMemberEntity> members = memberRepository.findByGroupIdOrderByCreatedAtAsc(groupId);
        List<ProductEntity> affected =
                members.stream().map(SubstitutionGroupMemberEntity::getProduct).toList();
        memberRepository.deleteAll(members);
        groupRepository.delete(group);
        memberRepository.flush();
        affected.forEach(this::touchAndPublish);
    }

    @Override
    @Transactional
    public SubstitutionGroupDto addMember(@NonNull UUID groupId, @NonNull SubstitutionGroupMemberRequestDto request) {
        SubstitutionGroupEntity group = findGroup(groupId);
        ProductEntity product = productRepository
                .findById(request.getProductId())
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + request.getProductId()));
        if (memberRepository.findByProductId(product.getId()).isPresent()) {
            throw new CatalogBusinessRuleException(
                    "Product " + product.getId() + " already belongs to a substitution group");
        }

        SubstitutionGroupMemberEntity member = new SubstitutionGroupMemberEntity();
        member.setGroup(group);
        member.setProduct(product);
        memberRepository.saveAndFlush(member);

        List<SubstitutionGroupMemberEntity> members = memberRepository.findByGroupIdOrderByCreatedAtAsc(groupId);
        members.forEach(m -> touchAndPublish(m.getProduct()));
        return toDto(group, members);
    }

    @Override
    @Transactional
    public SubstitutionGroupDto removeMember(@NonNull UUID groupId, @NonNull UUID productId) {
        SubstitutionGroupEntity group = findGroup(groupId);
        SubstitutionGroupMemberEntity member = memberRepository
                .findByGroupIdAndProductId(groupId, productId)
                .orElseThrow(() -> new CatalogNotFoundException(
                        "Product " + productId + " is not a member of substitution group " + groupId));
        ProductEntity removedProduct = member.getProduct();
        memberRepository.delete(member);
        memberRepository.flush();

        touchAndPublish(removedProduct);
        List<SubstitutionGroupMemberEntity> remaining = memberRepository.findByGroupIdOrderByCreatedAtAsc(groupId);
        remaining.forEach(m -> touchAndPublish(m.getProduct()));
        return toDto(group, remaining);
    }

    /**
     * Bump the product row's {@code updatedAt} before re-emitting its fact: the mutation must dirty
     * the row for the envelope's {@code aggregateVersion} — the product's JPA {@code @Version}
     * (#1486) — to actually advance when {@link CatalogFactPublisher} flushes, so membership-only
     * changes still advance it for consumers' stale-event guards.
     */
    private void touchAndPublish(ProductEntity product) {
        product.setUpdatedAt(AggregateTouch.monotonicUpdatedAt(product.getUpdatedAt(), clock));
        productRepository.saveAndFlush(product);
        catalogFactPublisher.publishProductUpdated(product);
    }

    private SubstitutionGroupEntity findGroup(UUID groupId) {
        return groupRepository
                .findById(groupId)
                .orElseThrow(() -> new CatalogNotFoundException("Substitution group not found: " + groupId));
    }

    private List<SubstitutionGroupMemberEntity> membersOf(UUID groupId) {
        return memberRepository.findByGroupIdOrderByCreatedAtAsc(groupId);
    }

    private SubstitutionGroupDto toDto(SubstitutionGroupEntity group, List<SubstitutionGroupMemberEntity> members) {
        return SubstitutionGroupDto.builder()
                .id(group.getId())
                .name(group.getName())
                .notes(group.getNotes())
                .productIds(members.stream()
                        .map(member -> member.getProduct().getId())
                        .toList())
                .createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt())
                .build();
    }
}
