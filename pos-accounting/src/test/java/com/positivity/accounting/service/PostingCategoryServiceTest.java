package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.dto.PostingCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryListResponse;
import com.positivity.accounting.internal.dto.PostingCategoryResponse;
import com.positivity.accounting.internal.dto.PostingCategoryUpdateRequest;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.internal.service.PostingCategoryServiceImpl;

/**
 * Unit tests for PostingCategoryServiceImpl.
 *
 * Tests CRUD operations and lifecycle management for posting categories.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingCategoryService Unit Tests")
class PostingCategoryServiceTest {

    @Mock
    private PostingCategoryRepository postingCategoryRepository;

    @Mock
    private GLMappingRepository glMappingRepository;

    @InjectMocks
    private PostingCategoryServiceImpl service;

    private UUID testCategoryId;
    private PostingCategory testCategory;

    @BeforeEach
    void setUp() {
        testCategoryId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        testCategory = new PostingCategory();
        testCategory.setPostingCategoryId(testCategoryId);
        testCategory.setCategoryName("LABOR");
        testCategory.setDescription("Labor charges");
        testCategory.setIsActive(true);
        testCategory.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        testCategory.setCreatedBy("admin");
        testCategory.setUpdatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        testCategory.setModifiedBy("admin");
    }

    // ===== CREATE =====

    @Nested
    @DisplayName("createPostingCategory")
    class CreatePostingCategory {

        @Test
        @DisplayName("creates category successfully when name is unique")
        void success() {
            PostingCategoryCreateRequest request = PostingCategoryCreateRequest.builder()
                    .categoryName("  LABOR  ")
                    .description("Labor charges")
                    .createdBy("admin")
                    .build();

            when(postingCategoryRepository.existsByCategoryName("LABOR")).thenReturn(false);
            when(postingCategoryRepository.save(any(PostingCategory.class))).thenAnswer(inv -> {
                PostingCategory c = inv.getArgument(0);
                c.setPostingCategoryId(testCategoryId);
                return c;
            });

            PostingCategoryResponse result = service.createPostingCategory(request);

            assertThat(result).isNotNull();
            assertThat(result.getCategoryName()).isEqualTo("LABOR");
            assertThat(result.getIsActive()).isTrue();
            verify(postingCategoryRepository).existsByCategoryName("LABOR");
            verify(postingCategoryRepository).save(any(PostingCategory.class));
        }

        @Test
        @DisplayName("throws BAD_REQUEST when name already exists")
        void duplicateName_throwsBadRequest() {
            PostingCategoryCreateRequest request = PostingCategoryCreateRequest.builder()
                    .categoryName("LABOR")
                    .description("desc")
                    .createdBy("admin")
                    .build();

            when(postingCategoryRepository.existsByCategoryName("LABOR")).thenReturn(true);

            assertThatThrownBy(() -> service.createPostingCategory(request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("already exists");

            verify(postingCategoryRepository, never()).save(any());
        }
    }

    // ===== GET =====

    @Nested
    @DisplayName("getPostingCategory")
    class GetPostingCategory {

        @Test
        @DisplayName("returns category when found")
        void success() {
            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.of(testCategory));

            PostingCategoryResponse result = service.getPostingCategory(testCategoryId);

            assertThat(result).isNotNull();
            assertThat(result.getPostingCategoryId()).isEqualTo(testCategoryId);
            assertThat(result.getCategoryName()).isEqualTo("LABOR");
        }

        @Test
        @DisplayName("throws NOT_FOUND when category not found")
        void notFound() {
            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPostingCategory(testCategoryId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ===== UPDATE =====

    @Nested
    @DisplayName("updatePostingCategory")
    class UpdatePostingCategory {

        @Test
        @DisplayName("updates category successfully")
        void success() {
            PostingCategoryUpdateRequest request = PostingCategoryUpdateRequest.builder()
                    .categoryName("PARTS")
                    .description("Parts charges")
                    .modifiedBy("admin")
                    .build();

            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.of(testCategory));
            when(postingCategoryRepository.existsByCategoryName("PARTS")).thenReturn(false);
            when(postingCategoryRepository.save(any(PostingCategory.class))).thenReturn(testCategory);

            PostingCategoryResponse result = service.updatePostingCategory(testCategoryId, request);

            assertThat(result).isNotNull();
            verify(postingCategoryRepository).save(testCategory);
        }

        @Test
        @DisplayName("updates without checking uniqueness when name is unchanged")
        void sameNameSkipsUniquenessCheck() {
            PostingCategoryUpdateRequest request = PostingCategoryUpdateRequest.builder()
                    .categoryName("LABOR")
                    .description("Updated description")
                    .modifiedBy("admin")
                    .build();

            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.of(testCategory));
            when(postingCategoryRepository.save(any(PostingCategory.class))).thenReturn(testCategory);

            PostingCategoryResponse result = service.updatePostingCategory(testCategoryId, request);

            assertThat(result).isNotNull();
            verify(postingCategoryRepository, never()).existsByCategoryName(any());
        }

        @Test
        @DisplayName("throws NOT_FOUND when category not found")
        void notFound() {
            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePostingCategory(testCategoryId,
                    PostingCategoryUpdateRequest.builder()
                            .categoryName("X").modifiedBy("admin").build()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("throws BAD_REQUEST when new name already exists")
        void nameConflict() {
            PostingCategoryUpdateRequest request = PostingCategoryUpdateRequest.builder()
                    .categoryName("PARTS")
                    .description("desc")
                    .modifiedBy("admin")
                    .build();

            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.of(testCategory));
            when(postingCategoryRepository.existsByCategoryName("PARTS")).thenReturn(true);

            assertThatThrownBy(() -> service.updatePostingCategory(testCategoryId, request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("already exists");
        }
    }

    // ===== LIST =====

    @Nested
    @DisplayName("listPostingCategories")
    class ListPostingCategories {

        @Test
        @DisplayName("lists all categories without filter")
        void allCategories() {
            Page<PostingCategory> page = new PageImpl<>(
                    List.of(testCategory), PageRequest.of(0, 10), 1);
            when(postingCategoryRepository.findAll(any(Pageable.class))).thenReturn(page);

            PostingCategoryListResponse result = service.listPostingCategories(0, 10, "categoryName", null);

            assertThat(result.getResults()).hasSize(1);
            assertThat(result.getTotalCount()).isEqualTo(1L);
            assertThat(result.getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("lists categories filtered by isActive")
        void withIsActiveFilter() {
            Page<PostingCategory> page = new PageImpl<>(
                    List.of(testCategory), PageRequest.of(0, 10), 1);
            when(postingCategoryRepository.findAll(
                    any(org.springframework.data.jpa.domain.Specification.class),
                    any(Pageable.class))).thenReturn(page);

            PostingCategoryListResponse result = service.listPostingCategories(0, 10, "categoryName", true);

            assertThat(result.getResults()).hasSize(1);
        }
    }

    // ===== DEACTIVATE =====

    @Nested
    @DisplayName("deactivatePostingCategory")
    class DeactivatePostingCategory {

        @Test
        @DisplayName("deactivates category when no active mappings exist")
        void success() {
            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.of(testCategory));
            when(glMappingRepository.countByPostingCategoryIdAndDeactivatedAtIsNull(testCategoryId))
                    .thenReturn(0L);
            when(postingCategoryRepository.save(testCategory)).thenReturn(testCategory);

            PostingCategoryResponse result = service.deactivatePostingCategory(testCategoryId);

            assertThat(result).isNotNull();
            verify(postingCategoryRepository).save(testCategory);
        }

        @Test
        @DisplayName("throws NOT_FOUND when category not found")
        void notFound() {
            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivatePostingCategory(testCategoryId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("throws CONFLICT when active GL mappings exist")
        void activeMappingsExist() {
            when(postingCategoryRepository.findById(testCategoryId))
                    .thenReturn(Optional.of(testCategory));
            when(glMappingRepository.countByPostingCategoryIdAndDeactivatedAtIsNull(testCategoryId))
                    .thenReturn(3L);

            assertThatThrownBy(() -> service.deactivatePostingCategory(testCategoryId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("active GL mappings");
        }
    }
}
