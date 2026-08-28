package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductReplacementEntity;
import com.positivity.catalog.internal.enums.ProductLifecycleState;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductReplacementRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Product lifecycle transitions. Discontinuation is a one-way door unless the caller holds
 * {@code product:lifecycle:override_discontinued} and states a reason, and every accepted
 * transition invalidates the product-detail cache and publishes a product fact.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductLifecycleServiceImpl")
class ProductLifecycleServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01");
    private static final UUID REPLACEMENT_PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a02");
    private static final UUID REPLACEMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a03");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String OVERRIDE_AUTHORITY = "product:lifecycle:override_discontinued";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductReplacementRepository productReplacementRepository;

    @Mock
    private ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher;

    @Mock
    private CatalogFactPublisher catalogFactPublisher;

    private MeterRegistry meterRegistry;
    private ProductLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ProductLifecycleServiceImpl(
                productRepository,
                productReplacementRepository,
                meterRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC),
                productDetailCacheInvalidationPublisher,
                catalogFactPublisher);
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productReplacementRepository.save(any(ProductReplacementEntity.class)))
                .thenAnswer(inv -> {
                    ProductReplacementEntity entity = inv.getArgument(0);
                    ReflectionTestUtils.setField(entity, "replacementId", REPLACEMENT_ID);
                    return entity;
                });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The gateway hands services the audit identity in the authentication details map
     * ({@code X-User} → {@code username}); {@code SecurityContextHelper} reads that map first
     * and falls back to the default when it is absent, so a realistic token must carry it.
     */
    private static void authenticateAs(String username, String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static ProductEntity product(UUID id, ProductLifecycleState state) {
        ProductEntity product = new ProductEntity();
        product.setId(id);
        product.setName("Tire 205/55R16");
        product.setSku("SKU-" + id);
        product.setLifecycleState(state);
        return product;
    }

    private void stubProduct(ProductEntity product) {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
    }

    private static ProductLifecycleUpdateRequest updateRequest(ProductLifecycleState state) {
        ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
        request.setLifecycleState(state);
        request.setEffectiveAt(NOW);
        return request;
    }

    private double counter(String name) {
        return meterRegistry.counter(name).count();
    }

    @Nested
    @DisplayName("getLifecycle")
    class GetLifecycle {

        @Test
        void reportsTheStateAndItsReplacementOptions() {
            ProductEntity product = product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED);
            product.setLifecycleStateEffectiveAt(NOW);
            product.setLifecycleOverrideReason("End of line");
            stubProduct(product);

            ProductReplacementEntity replacement = new ProductReplacementEntity();
            ReflectionTestUtils.setField(replacement, "replacementId", REPLACEMENT_ID);
            replacement.setReplacementProduct(product(REPLACEMENT_PRODUCT_ID, ProductLifecycleState.ACTIVE));
            replacement.setPriorityOrder(1);
            replacement.setNotes("Successor SKU");
            replacement.setEffectiveAt(NOW);
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of(replacement));

            ProductLifecycleResponse response = service.getLifecycle(PRODUCT_ID);

            assertThat(response.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(response.getLifecycleState()).isEqualTo(ProductLifecycleState.DISCONTINUED);
            assertThat(response.getLifecycleOverrideReason()).isEqualTo("End of line");
            assertThat(response.getReplacementOptions()).hasSize(1);
            assertThat(response.getReplacementOptions().get(0).getReplacementProductId())
                    .isEqualTo(REPLACEMENT_PRODUCT_ID);
            assertThat(response.getReplacementOptions().get(0).getNotes()).isEqualTo("Successor SKU");
        }

        @Test
        void treatsAProductWithNoRecordedStateAsActive() {
            stubProduct(product(PRODUCT_ID, null));
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());

            assertThat(service.getLifecycle(PRODUCT_ID).getLifecycleState()).isEqualTo(ProductLifecycleState.ACTIVE);
        }

        @Test
        void failsForAnUnknownProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getLifecycle(PRODUCT_ID))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("Product not found");
        }
    }

    @Nested
    @DisplayName("updateLifecycle")
    class UpdateLifecycle {

        @Test
        void movesAnActiveProductToInactiveAndPublishesTheChange() {
            ProductEntity product = product(PRODUCT_ID, ProductLifecycleState.ACTIVE);
            stubProduct(product);
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());
            authenticateAs("jane.smith");

            ProductLifecycleResponse response =
                    service.updateLifecycle(PRODUCT_ID, updateRequest(ProductLifecycleState.INACTIVE));

            assertThat(product.getLifecycleState()).isEqualTo(ProductLifecycleState.INACTIVE);
            assertThat(product.getLifecycleStateEffectiveAt()).isEqualTo(NOW);
            assertThat(product.getLastStateChangedAt()).isEqualTo(NOW);
            // A non-UUID principal is hashed to a stable actor id.
            assertThat(product.getLastStateChangedBy())
                    .isEqualTo(UUID.nameUUIDFromBytes("jane.smith".getBytes(StandardCharsets.UTF_8)));
            assertThat(response.getLifecycleState()).isEqualTo(ProductLifecycleState.INACTIVE);
            verify(productDetailCacheInvalidationPublisher).invalidateProduct(PRODUCT_ID);
            verify(catalogFactPublisher).publishProductUpdated(product);
            assertThat(counter("product.lifecycle.state_change.success.count")).isEqualTo(1.0d);
        }

        @Test
        void keepsAUuidPrincipalAsTheActorId() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());
            authenticateAs(REPLACEMENT_PRODUCT_ID.toString());

            service.updateLifecycle(PRODUCT_ID, updateRequest(ProductLifecycleState.INACTIVE));

            verify(productRepository)
                    .save(org.mockito.ArgumentMatchers.argThat(
                            saved -> REPLACEMENT_PRODUCT_ID.equals(saved.getLastStateChangedBy())));
        }

        @Test
        void acceptsAnEffectiveDateInsteadOfAnEffectiveInstant() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());
            ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
            request.setLifecycleState(ProductLifecycleState.INACTIVE);
            request.setEffectiveDate(LocalDate.of(2026, 3, 2));

            service.updateLifecycle(PRODUCT_ID, request);

            verify(productRepository)
                    .save(org.mockito.ArgumentMatchers.argThat(saved ->
                            Instant.parse("2026-03-02T00:00:00Z").equals(saved.getLifecycleStateEffectiveAt())));
        }

        @Test
        void rejectsANullRequest() {
            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, null))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("request is required");
            assertThat(counter("product.lifecycle.state_change.denied.count")).isEqualTo(1.0d);
        }

        @Test
        void rejectsARequestWithNoTargetState() {
            ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
            request.setEffectiveAt(NOW);

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("lifecycleState is required");
        }

        @Test
        void rejectsARequestWithNeitherAnEffectiveInstantNorDate() {
            ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
            request.setLifecycleState(ProductLifecycleState.INACTIVE);

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("either effectiveAt or effectiveDate is required");
        }

        @Test
        void rejectsATransitionToTheStateTheProductIsAlreadyIn() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));

            ProductLifecycleUpdateRequest request = updateRequest(ProductLifecycleState.ACTIVE);

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("already set to ACTIVE");
        }

        @Test
        void refusesToReactivateADiscontinuedProduct() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            authenticateAs("jane.smith", OVERRIDE_AUTHORITY);

            ProductLifecycleUpdateRequest request = updateRequest(ProductLifecycleState.ACTIVE);

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("cannot be reactivated");
            verify(productRepository, never()).save(any());
        }

        @Test
        void refusesToDiscontinueWithoutTheOverridePermission() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            authenticateAs("jane.smith");

            ProductLifecycleUpdateRequest request = updateRequest(ProductLifecycleState.DISCONTINUED);
            request.setOverrideReason("End of line");

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogForbiddenOperationException.class)
                    .hasMessageContaining(OVERRIDE_AUTHORITY);
        }

        @Test
        void refusesToDiscontinueWhenThereIsNoAuthenticationAtAll() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));

            ProductLifecycleUpdateRequest request = updateRequest(ProductLifecycleState.DISCONTINUED);
            request.setOverrideReason("End of line");

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogForbiddenOperationException.class);
        }

        @Test
        void refusesToDiscontinueWithoutAStatedReason() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            authenticateAs("jane.smith", OVERRIDE_AUTHORITY);

            ProductLifecycleUpdateRequest request = updateRequest(ProductLifecycleState.DISCONTINUED);
            request.setOverrideReason("   ");

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("overrideReason is required");
        }

        @Test
        void discontinuesWhenThePermissionAndReasonAreBothPresent() {
            ProductEntity product = product(PRODUCT_ID, ProductLifecycleState.ACTIVE);
            stubProduct(product);
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());
            authenticateAs("jane.smith", OVERRIDE_AUTHORITY);

            ProductLifecycleUpdateRequest request = updateRequest(ProductLifecycleState.DISCONTINUED);
            request.setOverrideReason("End of line");

            assertThat(service.updateLifecycle(PRODUCT_ID, request).getLifecycleState())
                    .isEqualTo(ProductLifecycleState.DISCONTINUED);
            assertThat(product.getLifecycleOverrideReason()).isEqualTo("End of line");
        }

        @Test
        void rejectsAnEffectiveInstantInThePast() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));

            ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
            request.setLifecycleState(ProductLifecycleState.INACTIVE);
            request.setEffectiveAt(NOW.minusSeconds(600));

            assertThatThrownBy(() -> service.updateLifecycle(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("effectiveAt cannot be in the past");
        }

        @Test
        void toleratesAnEffectiveInstantASecondBehindTheClock() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());

            ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
            request.setLifecycleState(ProductLifecycleState.INACTIVE);
            request.setEffectiveAt(NOW.minusSeconds(1));

            assertThat(service.updateLifecycle(PRODUCT_ID, request)).isNotNull();
        }
    }

    @Nested
    @DisplayName("setLifecycleState")
    class SetLifecycleState {

        @Test
        void defaultsTheEffectiveDateToTodayWhenNoneIsGiven() {
            ProductEntity product = product(PRODUCT_ID, ProductLifecycleState.ACTIVE);
            stubProduct(product);
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());

            ProductLifecycleResponse response =
                    service.setLifecycleState(PRODUCT_ID, ProductLifecycleState.INACTIVE, NOW, null, null);

            assertThat(response.getLifecycleState()).isEqualTo(ProductLifecycleState.INACTIVE);
        }

        @Test
        void passesAnExplicitEffectiveDateThrough() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of());

            service.setLifecycleState(PRODUCT_ID, ProductLifecycleState.INACTIVE, null, null, LocalDate.of(2026, 3, 5));

            verify(productRepository)
                    .save(org.mockito.ArgumentMatchers.argThat(saved ->
                            Instant.parse("2026-03-05T00:00:00Z").equals(saved.getLifecycleStateEffectiveAt())));
        }
    }

    @Nested
    @DisplayName("replacements")
    class Replacements {

        private ProductReplacementRequest replacementRequest() {
            ProductReplacementRequest request = new ProductReplacementRequest();
            request.setReplacementProductId(REPLACEMENT_PRODUCT_ID);
            request.setPriorityOrder(1);
            request.setNotes("Successor SKU");
            request.setEffectiveAt(NOW);
            return request;
        }

        @Test
        void addsAReplacementToADiscontinuedProduct() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            stubProduct(product(REPLACEMENT_PRODUCT_ID, ProductLifecycleState.ACTIVE));

            ProductLifecycleResponse.ReplacementOption option =
                    service.addReplacement(PRODUCT_ID, replacementRequest());

            assertThat(option.getReplacementId()).isEqualTo(REPLACEMENT_ID);
            assertThat(option.getReplacementProductId()).isEqualTo(REPLACEMENT_PRODUCT_ID);
            assertThat(option.getPriorityOrder()).isEqualTo(1);
            assertThat(option.getEffectiveAt()).isEqualTo(NOW);
            verify(productDetailCacheInvalidationPublisher).invalidateProduct(PRODUCT_ID);
        }

        @Test
        void stampsTheCurrentInstantWhenNoEffectiveAtIsGiven() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            stubProduct(product(REPLACEMENT_PRODUCT_ID, ProductLifecycleState.ACTIVE));
            ProductReplacementRequest request = replacementRequest();
            request.setEffectiveAt(null);

            assertThat(service.addReplacement(PRODUCT_ID, request).getEffectiveAt())
                    .isEqualTo(NOW);
        }

        @Test
        void rejectsARequestWithoutAReplacementProduct() {
            ProductReplacementRequest request = new ProductReplacementRequest();
            request.setPriorityOrder(1);

            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("replacementProductId is required");
        }

        @Test
        void rejectsANullRequest() {
            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, null))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("replacementProductId is required");
        }

        @Test
        void rejectsAProductReplacingItself() {
            ProductReplacementRequest request = replacementRequest();
            request.setReplacementProductId(PRODUCT_ID);

            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("cannot be identical");
        }

        @Test
        void rejectsANonPositivePriorityOrder() {
            ProductReplacementRequest request = replacementRequest();
            request.setPriorityOrder(0);

            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("priorityOrder must be greater than zero");
        }

        @Test
        void rejectsAMissingPriorityOrder() {
            ProductReplacementRequest request = replacementRequest();
            request.setPriorityOrder(null);

            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("priorityOrder must be greater than zero");
        }

        @Test
        void refusesToAddAReplacementToAProductThatIsStillActive() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.ACTIVE));
            ProductReplacementRequest request = replacementRequest();

            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, request))
                    .isInstanceOf(CatalogBusinessRuleException.class)
                    .hasMessageContaining("only be added when lifecycleState is DISCONTINUED");
        }

        @Test
        void failsWhenTheReplacementProductDoesNotExist() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            when(productRepository.findById(REPLACEMENT_PRODUCT_ID)).thenReturn(Optional.empty());
            ProductReplacementRequest request = replacementRequest();

            assertThatThrownBy(() -> service.addReplacement(PRODUCT_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class);
        }

        @Test
        void addsAReplacementThroughTheServiceToServiceOverload() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            stubProduct(product(REPLACEMENT_PRODUCT_ID, ProductLifecycleState.ACTIVE));

            ProductLifecycleResponse.ReplacementOption option =
                    service.addReplacementProduct(PRODUCT_ID, REPLACEMENT_PRODUCT_ID, 2, "Superseded", NOW);

            assertThat(option.getPriorityOrder()).isEqualTo(2);
            assertThat(option.getNotes()).isEqualTo("Superseded");
        }

        @Test
        void listsTheReplacementsRecordedForAProduct() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            ProductReplacementEntity replacement = new ProductReplacementEntity();
            ReflectionTestUtils.setField(replacement, "replacementId", REPLACEMENT_ID);
            replacement.setReplacementProduct(product(REPLACEMENT_PRODUCT_ID, ProductLifecycleState.ACTIVE));
            replacement.setPriorityOrder(1);
            replacement.setEffectiveAt(NOW);
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of(replacement));

            List<ProductLifecycleResponse.ReplacementOption> options = service.getReplacementProducts(PRODUCT_ID);

            assertThat(options).hasSize(1);
            assertThat(options.get(0).getReplacementProductId()).isEqualTo(REPLACEMENT_PRODUCT_ID);
        }

        @Test
        void tolueratesAReplacementRowWithNoTargetProduct() {
            stubProduct(product(PRODUCT_ID, ProductLifecycleState.DISCONTINUED));
            ProductReplacementEntity orphaned = new ProductReplacementEntity();
            ReflectionTestUtils.setField(orphaned, "replacementId", REPLACEMENT_ID);
            orphaned.setPriorityOrder(1);
            when(productReplacementRepository.findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(
                            PRODUCT_ID))
                    .thenReturn(List.of(orphaned));

            assertThat(service.getReplacementProducts(PRODUCT_ID).get(0).getReplacementProductId())
                    .isNull();
        }
    }
}
