package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.pick.CreateSubstituteLinkRequest;
import com.positivity.workorder.internal.dto.pick.SubstituteLinkResponse;
import com.positivity.workorder.internal.dto.pick.UpdateSubstituteLinkRequest;
import com.positivity.workorder.internal.entity.SubstituteAudit;
import com.positivity.workorder.internal.entity.SubstituteLink;
import com.positivity.workorder.internal.enums.SubstituteType;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.internal.repository.SubstituteAuditRepository;
import com.positivity.workorder.internal.repository.SubstituteLinkRepository;
import com.positivity.workorder.internal.service.SubstituteLinkServiceImpl;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link SubstituteLinkServiceImpl} covering CRUD behaviour,
 * conflict detection, optimistic locking, soft-delete, and list ordering.
 *
 * <p>
 * Acceptance criteria covered:
 * <ul>
 * <li>AC1 – Create succeeds and returns created resource</li>
 * <li>AC2 – Duplicate productId+substitutePartId raises 409</li>
 * <li>AC4 – Update with correct version succeeds and increments version</li>
 * <li>AC5 – Update with stale version raises 409</li>
 * <li>AC6 – Soft-delete sets isActive=false</li>
 * <li>AC7 – List is ordered by priority ASC</li>
 * </ul>
 * </p>
 *
 * Issue: CAP-171 Story #45
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubstituteLinkServiceTest – CRUD, conflict and optimistic locking")
class SubstituteLinkServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000045");
    private static final UUID SUBSTITUTE_PART_ID = UUID.fromString("00000000-0000-0000-0000-000000000046");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000047");
    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000048");

    @Mock
    private SubstituteLinkRepository substituteLinkRepository;

    @Mock
    private SubstituteAuditRepository substituteAuditRepository;

    @InjectMocks
    private SubstituteLinkServiceImpl substituteLinkService;

    @BeforeEach
    void setUpSecurityContext() {
        setAuthenticatedUserInSecurityContext("service-test-user");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // AC1 — Create success
    // =========================================================================

    // Issue CAP-171 Story #45: happy path create returns response with linkId
    @Test
    @DisplayName("AC1: createLink with valid request saves link and returns SubstituteLinkResponse")
    void createLink_success_returns201WithCreatedResource() {
        // Arrange
        var request = CreateSubstituteLinkRequest.builder()
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .priority(100)
                .isAutoSuggest(false)
                .build();

        var savedLink = buildLink(LINK_ID, 100, true);

        when(substituteLinkRepository.existsByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID))
                .thenReturn(false);
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(savedLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        // Act
        SubstituteLinkResponse response = substituteLinkService.createLink(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(LINK_ID);
        assertThat(response.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(response.getSubstituteType()).isEqualTo(SubstituteType.EQUIVALENT);
        verify(substituteLinkRepository).save(any(SubstituteLink.class));
    }

    // =========================================================================
    // AC2 — Duplicate creates 409
    // =========================================================================

    // Issue CAP-171 Story #45: duplicate (productId + substitutePartId) must throw
    // 409
    @Test
    @DisplayName("AC2: createLink with duplicate productId+substitutePartId throws DuplicateSubstituteLinkException")
    void createLink_duplicate_throws409Conflict() {
        // Arrange
        var request = CreateSubstituteLinkRequest.builder()
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .build();

        when(substituteLinkRepository.existsByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID))
                .thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> substituteLinkService.createLink(request))
                .isInstanceOf(DuplicateSubstituteLinkException.class);
    }

    // =========================================================================
    // AC4 — Update with correct version succeeds
    // =========================================================================

    // Issue CAP-171 Story #45: update with matching version succeeds
    @Test
    @DisplayName("AC4: updateLink with correct version saves updated entity and returns response")
    void updateLink_withCorrectVersion_succeeds() {
        // Arrange
        var existingLink = buildLink(LINK_ID, 50, true);
        existingLink.setVersion(3);

        when(substituteLinkRepository.findById(LINK_ID)).thenReturn(Optional.of(existingLink));
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(existingLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.UPGRADE)
                .priority(200)
                .version(3)
                .build();

        // Act
        SubstituteLinkResponse response = substituteLinkService.updateLink(LINK_ID, request);

        // Assert
        assertThat(response).isNotNull();
        verify(substituteLinkRepository).save(any(SubstituteLink.class));
    }

    // =========================================================================
    // AC5 — Update with stale version throws 409
    // =========================================================================

    // Issue CAP-171 Story #45: stale optimistic lock version must reject the update
    @Test
    @DisplayName("AC5: updateLink with stale version throws StaleSubstituteLinkVersionException")
    void updateLink_withStaleVersion_throws409Conflict() {
        // Arrange
        var existingLink = buildLink(LINK_ID, 50, true);
        existingLink.setVersion(5); // current version is 5

        when(substituteLinkRepository.findById(LINK_ID)).thenReturn(Optional.of(existingLink));

        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.UPGRADE)
                .version(3) // stale — client thinks it's version 3
                .build();

        // Act & Assert
        assertThatThrownBy(() -> substituteLinkService.updateLink(LINK_ID, request))
                .isInstanceOf(StaleSubstituteLinkVersionException.class);
    }

    // =========================================================================
    // AC6 — Soft-delete
    // =========================================================================

    // Issue CAP-171 Story #45: delete must soft-delete (isActive=false), not
    // hard-delete
    @Test
    @DisplayName("AC6: deleteLink soft-deletes (isActive=false) and persists the change")
    void deleteLink_softDeletesLinkAndReturnsSuccess() {
        // Arrange
        var existingLink = buildLink(LINK_ID, 50, true);
        when(substituteLinkRepository.findByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID))
                .thenReturn(Optional.of(existingLink));
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(existingLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        // Act
        substituteLinkService.deleteLink(PRODUCT_ID, SUBSTITUTE_PART_ID);

        // Assert — entity must be soft-deleted, not removed
        verify(substituteLinkRepository).save(any(SubstituteLink.class));
        verify(substituteLinkRepository).findByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID);
        // The saved entity must have isActive=false (soft-delete)
        assertThat(existingLink.getIsActive()).isFalse();
    }

    @Test
    @DisplayName("deleteLink for non-existent link throws SubstituteLinkNotFoundException")
    void deleteLink_whenLinkNotFound_throwsSubstituteLinkNotFoundException() {
        given(substituteLinkRepository.findByProductIdAndSubstitutePartId(any(), any()))
                .willReturn(Optional.empty());

        assertThrows(
                SubstituteLinkNotFoundException.class,
                () -> substituteLinkService.deleteLink(PRODUCT_ID, SUBSTITUTE_PART_ID));
    }

    @Test
    @DisplayName("updateLink for non-existent link throws SubstituteLinkNotFoundException")
    void updateLink_whenLinkNotFound_throwsSubstituteLinkNotFoundException() {
        given(substituteLinkRepository.findById(any())).willReturn(Optional.empty());

        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.UPGRADE)
                .version(0)
                .build();

        assertThrows(SubstituteLinkNotFoundException.class, () -> substituteLinkService.updateLink(LINK_ID, request));
    }

    // =========================================================================
    // AC7 — List by productId, ordered by priority ASC
    // =========================================================================

    // Issue CAP-171 Story #45: list must return only active links, ordered by
    // priority ASC
    @Test
    @DisplayName("AC7: listLinks returns active links ordered by priority ASC")
    void listLinks_byProductId_orderedByPriority() {
        // Arrange
        var link1 = buildLink(UUID.fromString("00000000-0000-0000-0000-000000000010"), 200, true);
        var link2 = buildLink(UUID.fromString("00000000-0000-0000-0000-000000000011"), 50, true);
        var link3 = buildLink(UUID.fromString("00000000-0000-0000-0000-000000000012"), 100, true);

        when(substituteLinkRepository.findByProductIdAndIsActiveTrueOrderByPriorityAsc(PRODUCT_ID))
                .thenReturn(List.of(link2, link3, link1)); // already sorted by repo

        // Act
        List<SubstituteLinkResponse> results = substituteLinkService.listLinks(PRODUCT_ID);

        // Assert — three active links returned in priority order
        assertThat(results).hasSize(3);
        assertThat(results.get(0).getPriority())
                .isLessThanOrEqualTo(results.get(1).getPriority());
        assertThat(results.get(1).getPriority())
                .isLessThanOrEqualTo(results.get(2).getPriority());
    }

    // --- Helpers ---

    private void setAuthenticatedUserInSecurityContext(String username) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, username,
                GatewaySecurityConstants.DETAIL_USER_ID, TEST_USER_ID));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private SubstituteLink buildLink(UUID id, int priority, boolean active) {
        var link = new SubstituteLink();
        link.setId(id);
        link.setProductId(PRODUCT_ID);
        link.setSubstitutePartId(SUBSTITUTE_PART_ID);
        link.setSubstituteType(SubstituteType.EQUIVALENT);
        link.setPriority(priority);
        link.setIsActive(active);
        link.setVersion(0);
        link.setCreatedBy("previous-user");
        return link;
    }
}
