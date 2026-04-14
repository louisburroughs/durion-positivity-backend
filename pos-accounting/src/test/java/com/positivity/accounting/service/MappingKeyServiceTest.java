package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.MappingKeyCreateRequest;
import com.positivity.accounting.internal.dto.MappingKeyListResponse;
import com.positivity.accounting.internal.dto.MappingKeyResponse;
import com.positivity.accounting.internal.dto.MappingKeyUpdateRequest;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.service.MappingKeyServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for MappingKeyService
 *
 * Tests CRUD operations, uniqueness validation, and deactivation
 * with active mapping checks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MappingKeyService Unit Tests")
class MappingKeyServiceTest {

    @Mock
    private MappingKeyRepository mappingKeyRepository;

    @Mock
    private PostingCategoryRepository postingCategoryRepository;

    @Mock
    private GLMappingRepository glMappingRepository;

    @InjectMocks
    private MappingKeyServiceImpl service;

    private UUID testCategoryId;
    private UUID testMappingKeyId;
    private PostingCategory testCategory;
    private MappingKey testMappingKey;

    @BeforeEach
    void setUp() {
        testCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testMappingKeyId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        testCategory = new PostingCategory();
        testCategory.setPostingCategoryId(testCategoryId);
        testCategory.setCategoryName("Test Category");
        testCategory.setIsActive(true);

        testMappingKey = new MappingKey();
        testMappingKey.setMappingKeyId(testMappingKeyId);
        testMappingKey.setPostingCategoryId(testCategoryId);
        testMappingKey.setKeyName("TEST_KEY");
        testMappingKey.setDescription("Test mapping key");
        testMappingKey.setIsActive(true);
    }

    // ===== CREATE TESTS =====

    @Test
    @DisplayName("createMappingKey - creates key successfully")
    void createMappingKey_success() {
        // Arrange
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(testCategoryId);
        request.setKeyName("NEW_KEY");
        request.setDescription("New test key");
        request.setCreatedBy("test-user");

        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(testCategoryId, "NEW_KEY"))
                .thenReturn(false);
        when(mappingKeyRepository.save(any(MappingKey.class))).thenAnswer(inv -> {
            MappingKey key = inv.getArgument(0);
            key.setMappingKeyId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            return key;
        });

        // Act
        MappingKeyResponse response = service.createMappingKey(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getKeyName()).isEqualTo("NEW_KEY");
        assertThat(response.getPostingCategoryName()).isEqualTo("Test Category");
        verify(mappingKeyRepository).save(any(MappingKey.class));
    }

    @Test
    @DisplayName("createMappingKey - trims key name")
    void createMappingKey_trimsName() {
        // Arrange
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(testCategoryId);
        request.setKeyName("  SPACED_KEY  ");
        request.setDescription("Test");
        request.setCreatedBy("test-user");

        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(testCategoryId, "SPACED_KEY"))
                .thenReturn(false);
        when(mappingKeyRepository.save(any(MappingKey.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.createMappingKey(request);

        // Assert
        verify(mappingKeyRepository).existsByPostingCategory_PostingCategoryIdAndKeyName(testCategoryId, "SPACED_KEY");
    }

    @Test
    @DisplayName("createMappingKey - rejects when category not found")
    void createMappingKey_categoryNotFound_throwsException() {
        // Arrange
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(testCategoryId);
        request.setKeyName("KEY");

        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.createMappingKey(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Posting category not found")
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("createMappingKey - rejects duplicate key name in category")
    void createMappingKey_duplicateName_throwsException() {
        // Arrange
        MappingKeyCreateRequest request = new MappingKeyCreateRequest();
        request.setPostingCategoryId(testCategoryId);
        request.setKeyName("EXISTING_KEY");

        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(testCategoryId, "EXISTING_KEY"))
                .thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> service.createMappingKey(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists")
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    // ===== GET TESTS =====

    @Test
    @DisplayName("getMappingKey - retrieves key successfully")
    void getMappingKey_found_success() {
        // Arrange
        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.of(testMappingKey));
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));

        // Act
        MappingKeyResponse response = service.getMappingKey(testMappingKeyId);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getMappingKeyId()).isEqualTo(testMappingKeyId);
        assertThat(response.getKeyName()).isEqualTo("TEST_KEY");
    }

    @Test
    @DisplayName("getMappingKey - throws when key not found")
    void getMappingKey_notFound_throwsException() {
        // Arrange
        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.getMappingKey(testMappingKeyId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Mapping key not found")
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
    }

    // ===== UPDATE TESTS =====

    @Test
    @DisplayName("updateMappingKey - updates successfully")
    void updateMappingKey_success() {
        // Arrange
        MappingKeyUpdateRequest request = new MappingKeyUpdateRequest();
        request.setKeyName("UPDATED_KEY");
        request.setDescription("Updated description");
        request.setModifiedBy("updater");

        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.of(testMappingKey));
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(testCategoryId, "UPDATED_KEY"))
                .thenReturn(false);
        when(mappingKeyRepository.save(any(MappingKey.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        MappingKeyResponse response = service.updateMappingKey(testMappingKeyId, request);

        // Assert
        assertThat(response.getKeyName()).isEqualTo("UPDATED_KEY");
        verify(mappingKeyRepository).save(any(MappingKey.class));
    }

    @Test
    @DisplayName("updateMappingKey - allows keeping same name")
    void updateMappingKey_sameName_success() {
        // Arrange
        MappingKeyUpdateRequest request = new MappingKeyUpdateRequest();
        request.setKeyName("TEST_KEY"); // Same as current
        request.setDescription("New description");
        request.setModifiedBy("updater");

        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.of(testMappingKey));
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.save(any(MappingKey.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.updateMappingKey(testMappingKeyId, request);

        // Assert - should NOT check for duplicates when name unchanged
        verify(mappingKeyRepository).save(any(MappingKey.class));
    }

    @Test
    @DisplayName("updateMappingKey - rejects duplicate new name")
    void updateMappingKey_duplicateName_throwsException() {
        // Arrange
        MappingKeyUpdateRequest request = new MappingKeyUpdateRequest();
        request.setKeyName("OTHER_KEY");
        request.setDescription("Test");
        request.setModifiedBy("updater");

        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.of(testMappingKey));
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(testCategoryId, "OTHER_KEY"))
                .thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> service.updateMappingKey(testMappingKeyId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    // ===== LIST TESTS =====

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("listMappingKeysByCategory - returns paginated results")
    void listMappingKeysByCategory_success() {
        // Arrange
        List<MappingKey> keys = List.of(testMappingKey);
        Page<MappingKey> page = new PageImpl<>(keys);

        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        // Act
        MappingKeyListResponse response = service.listMappingKeysByCategory(testCategoryId, 0, 10, "keyName", null);

        // Assert
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getTotalCount()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("listMappingKeysByCategory - filters by active status")
    void listMappingKeysByCategory_filterActive_success() {
        // Arrange
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(mappingKeyRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testMappingKey)));

        // Act
        MappingKeyListResponse response = service.listMappingKeysByCategory(testCategoryId, 0, 10, "keyName", true);

        // Assert
        assertThat(response.getResults()).hasSize(1);
    }

    // ===== DEACTIVATE TESTS =====

    @Test
    @DisplayName("deactivateMappingKey - deactivates when no active mappings")
    void deactivateMappingKey_noActiveMappings_success() {
        // Arrange
        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.of(testMappingKey));
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(glMappingRepository.countByMappingKeyIdAndDeactivatedAtIsNull(testMappingKeyId))
                .thenReturn(0L);
        when(mappingKeyRepository.save(any(MappingKey.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        MappingKeyResponse response = service.deactivateMappingKey(testMappingKeyId);

        // Assert
        assertThat(response).isNotNull();
        verify(mappingKeyRepository).save(any(MappingKey.class));
    }

    @Test
    @DisplayName("deactivateMappingKey - rejects when active mappings exist")
    void deactivateMappingKey_activeMappings_throwsException() {
        // Arrange
        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.of(testMappingKey));
        when(postingCategoryRepository.findById(testCategoryId)).thenReturn(Optional.of(testCategory));
        when(glMappingRepository.countByMappingKeyIdAndDeactivatedAtIsNull(testMappingKeyId))
                .thenReturn(5L);

        // Act & Assert
        assertThatThrownBy(() -> service.deactivateMappingKey(testMappingKeyId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active GL mappings")
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("deactivateMappingKey - throws when key not found")
    void deactivateMappingKey_notFound_throwsException() {
        // Arrange
        when(mappingKeyRepository.findById(testMappingKeyId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.deactivateMappingKey(testMappingKeyId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Mapping key not found");
    }
}
