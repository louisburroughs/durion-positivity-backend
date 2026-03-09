package com.positivity.workorder.service;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.dto.CreateSubstituteLinkRequest;
import com.positivity.workorder.dto.UpdateSubstituteLinkRequest;
import com.positivity.workorder.internal.entity.SubstituteAudit;
import com.positivity.workorder.internal.entity.SubstituteLink;
import com.positivity.workorder.internal.enums.SubstituteType;
import com.positivity.workorder.internal.repository.SubstituteAuditRepository;
import com.positivity.workorder.internal.repository.SubstituteLinkRepository;
import com.positivity.workorder.internal.service.SubstituteLinkServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying ADR-0018: actor fields (createdBy/updatedBy/actorId) are
 * sourced exclusively from {@code SecurityContextHelper}, not from request
 * body.
 *
 * <p>
 * Covers audit recording for create, update, and soft-delete operations on
 * {@link SubstituteLink}, asserting that {@link SubstituteAudit} entries are
 * persisted with the correct operation type, actor, and payload fields.
 * </p>
 *
 * Issue: CAP-171 Story #45
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubstituteLinkAuditTest – ADR-0018 actor sourcing and audit recording")
class SubstituteLinkAuditTest {

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
        setAuthenticatedUserInSecurityContext("audit-test-user");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // Issue CAP-171 Story #45: ADR-0018 — createdBy must come from SecurityContext
    @Test
    @DisplayName("AC8: createdBy is sourced from SecurityContext, not request body")
    void givenAuthenticatedUser_whenCreateSubstituteLink_thenCreatedByIsFromSecurityContext() {
        // Arrange
        var request = CreateSubstituteLinkRequest.builder()
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .build();

        var savedLink = buildPersistedLink(0);
        savedLink.setCreatedBy("audit-test-user");

        when(substituteLinkRepository.existsByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID))
                .thenReturn(false);
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(savedLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        // Act
        substituteLinkService.createLink(request);

        // Assert — createdBy on saved entity must come from SecurityContext, not
        // request
        ArgumentCaptor<SubstituteLink> linkCaptor = ArgumentCaptor.forClass(SubstituteLink.class);
        verify(substituteLinkRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getCreatedBy())
                .as("createdBy must equal security context username per ADR-0018")
                .isEqualTo("audit-test-user");
    }

    // Issue CAP-171 Story #45: ADR-0018 — updatedBy must come from SecurityContext
    @Test
    @DisplayName("AC8: updatedBy is sourced from SecurityContext, not request body")
    void givenAuthenticatedUser_whenUpdateSubstituteLink_thenUpdatedByIsFromSecurityContext() {
        // Arrange
        var existingLink = buildPersistedLink(0);
        when(substituteLinkRepository.findById(LINK_ID)).thenReturn(Optional.of(existingLink));
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(existingLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.UPGRADE)
                .version(0)
                .build();

        // Act
        substituteLinkService.updateLink(LINK_ID, request);

        // Assert — updatedBy on saved entity must equal SecurityContext username
        ArgumentCaptor<SubstituteLink> linkCaptor = ArgumentCaptor.forClass(SubstituteLink.class);
        verify(substituteLinkRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getUpdatedBy())
                .as("updatedBy must equal security context username per ADR-0018")
                .isEqualTo("audit-test-user");
    }

    // Issue CAP-171 Story #45: audit entry must be saved on create with CREATE
    // operation
    @Test
    @DisplayName("AC1: create operation records SubstituteAudit entry with operation=CREATE")
    void whenCreateSubstituteLink_thenAuditEntryIsRecorded() {
        // Arrange
        var request = CreateSubstituteLinkRequest.builder()
                .productId(PRODUCT_ID)
                .substitutePartId(SUBSTITUTE_PART_ID)
                .substituteType(SubstituteType.EQUIVALENT)
                .build();

        var savedLink = buildPersistedLink(0);

        when(substituteLinkRepository.existsByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID))
                .thenReturn(false);
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(savedLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        // Act
        substituteLinkService.createLink(request);

        // Assert — audit repository must receive a SubstituteAudit with
        // operation=CREATE
        ArgumentCaptor<SubstituteAudit> auditCaptor = ArgumentCaptor.forClass(SubstituteAudit.class);
        verify(substituteAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOperation())
                .as("audit operation must be CREATE")
                .isEqualTo("CREATE");
        assertThat(auditCaptor.getValue().getActorId())
                .as("audit actorId must equal SecurityContext username per ADR-0018")
                .isEqualTo("audit-test-user");
    }

    // Issue CAP-171 Story #45: update audit must capture both before and after
    // payloads
    @Test
    @DisplayName("AC4: update records SubstituteAudit entry with non-null payloadBefore and payloadAfter")
    void whenUpdateSubstituteLink_thenAuditEntryWithBeforeAfterPayload() {
        // Arrange
        var existingLink = buildPersistedLink(0);
        when(substituteLinkRepository.findById(LINK_ID)).thenReturn(Optional.of(existingLink));
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(existingLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        var request = UpdateSubstituteLinkRequest.builder()
                .substituteType(SubstituteType.UPGRADE)
                .version(0)
                .build();

        // Act
        substituteLinkService.updateLink(LINK_ID, request);

        // Assert — audit entry must contain non-null before and after payloads
        ArgumentCaptor<SubstituteAudit> auditCaptor = ArgumentCaptor.forClass(SubstituteAudit.class);
        verify(substituteAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOperation()).isEqualTo("UPDATE");
        assertThat(auditCaptor.getValue().getPayloadBefore())
                .as("payloadBefore must not be blank for UPDATE audit")
                .isNotBlank();
        assertThat(auditCaptor.getValue().getPayloadAfter())
                .as("payloadAfter must not be blank for UPDATE audit")
                .isNotBlank();
    }

    // Issue CAP-171 Story #45: soft-delete must record audit entry with DELETE
    // operation
    @Test
    @DisplayName("AC6: soft-delete records SubstituteAudit entry with operation=DELETE and actorId from SecurityContext")
    void whenDeleteSubstituteLink_thenAuditEntryWithDeleteOperation() {
        // Arrange
        var existingLink = buildPersistedLink(0);
        when(substituteLinkRepository.findByProductIdAndSubstitutePartId(PRODUCT_ID, SUBSTITUTE_PART_ID))
                .thenReturn(Optional.of(existingLink));
        when(substituteLinkRepository.save(any(SubstituteLink.class))).thenReturn(existingLink);
        when(substituteAuditRepository.save(any(SubstituteAudit.class))).thenReturn(new SubstituteAudit());

        // Act
        substituteLinkService.deleteLink(PRODUCT_ID, SUBSTITUTE_PART_ID);

        // Assert — audit entry must have DELETE operation and actorId from
        // SecurityContext
        ArgumentCaptor<SubstituteAudit> auditCaptor = ArgumentCaptor.forClass(SubstituteAudit.class);
        verify(substituteAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOperation()).isEqualTo("DELETE");
        assertThat(auditCaptor.getValue().getActorId())
                .as("actorId must equal SecurityContext username per ADR-0018")
                .isEqualTo("audit-test-user");
        assertThat(auditCaptor.getValue().getPayloadBefore())
                .as("payloadBefore must capture pre-delete state with isActive=true")
                .contains("\"isActive\":true");
        assertThat(auditCaptor.getValue().getPayloadAfter())
                .as("payloadAfter must capture post-delete state with isActive=false")
                .contains("\"isActive\":false");
    }

    // --- Helpers ---

    /**
     * Sets a synthetic authenticated user in the Spring SecurityContext using the
     * details map pattern required by {@code SecurityContextHelper}.
     *
     * @param username the username to inject as the authenticated actor
     */
    private void setAuthenticatedUserInSecurityContext(String username) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(username, null,
                Collections.emptyList());
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, username,
                GatewaySecurityConstants.DETAIL_USER_ID, TEST_USER_ID));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * Builds a minimal persisted {@link SubstituteLink} for use in update/delete
     * tests.
     *
     * @param version the optimistic locking version to assign
     * @return a configured SubstituteLink instance
     */
    private SubstituteLink buildPersistedLink(int version) {
        var link = new SubstituteLink();
        link.setId(LINK_ID);
        link.setProductId(PRODUCT_ID);
        link.setSubstitutePartId(SUBSTITUTE_PART_ID);
        link.setSubstituteType(SubstituteType.EQUIVALENT);
        link.setIsActive(true);
        link.setVersion(version);
        link.setCreatedBy("previous-user");
        return link;
    }
}
