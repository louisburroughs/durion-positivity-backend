package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.SubstitutionGroupCreateRequestDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupMemberRequestDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Manages substitution groups of mutually interchangeable products (#1023, inventory Odoo-parity
 * Story X1). A product belongs to at most one group. Membership mutations re-emit
 * {@code catalog.product.updated} for every affected member so downstream replicas stay in sync.
 */
public interface SubstitutionGroupService {

    SubstitutionGroupDto createGroup(@NonNull SubstitutionGroupCreateRequestDto request);

    SubstitutionGroupDto getGroup(@NonNull UUID groupId);

    List<SubstitutionGroupDto> listGroups();

    void deleteGroup(@NonNull UUID groupId);

    SubstitutionGroupDto addMember(@NonNull UUID groupId, @NonNull SubstitutionGroupMemberRequestDto request);

    SubstitutionGroupDto removeMember(@NonNull UUID groupId, @NonNull UUID productId);
}
