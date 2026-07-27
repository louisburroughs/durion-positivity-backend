package com.positivity.catalog.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductStatus;
import com.positivity.catalog.internal.entity.ProductTrackingLevel;
import com.positivity.catalog.internal.entity.ProductUomEntity;
import com.positivity.catalog.internal.entity.ProductUomType;
import com.positivity.catalog.internal.entity.SubstitutionGroupEntity;
import com.positivity.catalog.internal.entity.SubstitutionGroupMemberEntity;
import com.positivity.catalog.internal.repository.ProductUomRepository;
import com.positivity.catalog.internal.repository.SubstitutionGroupMemberRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract test for the {@code catalog.product.updated} payload shape (#1023, Story X1).
 *
 * <p>Proves that schema version 2 is additive: every version-1 field keeps its name and position in
 * the serialized JSON, and the new UoM / tracking-level / substitution fields appear alongside them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogFactPublisher product contract payload")
class CatalogFactPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-22T10:15:30Z");

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriterProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private ProductUomRepository productUomRepository;

    @Mock
    private SubstitutionGroupMemberRepository substitutionGroupMemberRepository;

    private CatalogFactPublisher publisher;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        when(outboxEventWriterProvider.getIfAvailable()).thenReturn(outboxEventWriter);
        publisher = new CatalogFactPublisher(
                Clock.fixed(NOW, ZoneOffset.UTC),
                outboxEventWriterProvider,
                productUomRepository,
                substitutionGroupMemberRepository);
    }

    @Test
    @DisplayName("payload carries UoM conversions, tracking level, and substitution membership (schema v2)")
    void payloadCarriesNewContractFields() {
        ProductEntity product = product();
        UUID siblingId = UUID.fromString("00000000-0000-0000-0000-000000000bbb");
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-000000000ccc");

        ProductUomEntity caseUom = new ProductUomEntity();
        caseUom.setUomCode("CASE");
        caseUom.setUomType(ProductUomType.PURCHASE);
        caseUom.setFactorToBase(new BigDecimal("12.000000"));
        caseUom.setPrecisionScale(0);
        when(productUomRepository.findByProductIdOrderByUomCodeAsc(product.getId()))
                .thenReturn(List.of(caseUom));

        SubstitutionGroupEntity group = new SubstitutionGroupEntity();
        group.setId(groupId);
        SubstitutionGroupMemberEntity self = member(group, product);
        ProductEntity sibling = new ProductEntity();
        sibling.setId(siblingId);
        SubstitutionGroupMemberEntity other = member(group, sibling);
        when(substitutionGroupMemberRepository.findByProductId(product.getId())).thenReturn(Optional.of(self));
        when(substitutionGroupMemberRepository.findByGroupIdOrderByCreatedAtAsc(groupId))
                .thenReturn(List.of(self, other));

        publisher.publishProductUpdated(product);

        DomainEventEnvelope<?> envelope = capturedEnvelope();
        assertThat(envelope.eventType()).isEqualTo("catalog.product.updated");
        assertThat(envelope.schemaVersion()).isEqualTo(2);
        assertThat(envelope.aggregateId()).isEqualTo(product.getId());
        assertThat(envelope.aggregateVersion()).isEqualTo(product.getUpdatedAt().toEpochMilli());

        JsonNode payload =
                objectMapper.readTree(objectMapper.writeValueAsString(envelope)).path("payload");
        assertThat(payload.path("baseUom").stringValue(null)).isEqualTo("EA");
        assertThat(payload.path("trackingLevel").stringValue(null)).isEqualTo("LOT");
        assertThat(payload.path("uomConversions")).hasSize(1);
        JsonNode conversion = payload.path("uomConversions").get(0);
        assertThat(conversion.path("uomCode").stringValue(null)).isEqualTo("CASE");
        assertThat(conversion.path("uomType").stringValue(null)).isEqualTo("PURCHASE");
        assertThat(conversion.path("factorToBase").decimalValue()).isEqualByComparingTo("12");
        assertThat(conversion.path("precisionScale").intValue()).isZero();
        assertThat(payload.path("substitutionGroupId").stringValue(null)).isEqualTo(groupId.toString());
        assertThat(payload.path("substitutionProductIds"))
                .extracting(node -> node.stringValue(null))
                .containsExactly(product.getId().toString(), siblingId.toString());
    }

    @Test
    @DisplayName("regression: every schema-v1 field survives with its original name (additive-only change)")
    void v1FieldsAreUnchanged() {
        ProductEntity product = product();
        when(productUomRepository.findByProductIdOrderByUomCodeAsc(product.getId()))
                .thenReturn(List.of());
        when(substitutionGroupMemberRepository.findByProductId(product.getId())).thenReturn(Optional.empty());

        publisher.publishProductUpdated(product);

        JsonNode payload = objectMapper
                .readTree(objectMapper.writeValueAsString(capturedEnvelope()))
                .path("payload");
        List<String> v1Fields = List.of(
                "productId",
                "sku",
                "name",
                "manufacturerId",
                "manufacturerName",
                "manufacturerBrand",
                "categoryId",
                "category",
                "warranty",
                "manufacturerWarranty",
                "active",
                "createdAt",
                "updatedAt");
        for (String field : v1Fields) {
            assertThat(payload.has(field))
                    .as("schema-v1 field '%s' must keep its name", field)
                    .isTrue();
        }
        assertThat(payload.path("productId").stringValue(null))
                .isEqualTo(product.getId().toString());
        assertThat(payload.path("sku").stringValue(null)).isEqualTo("SKU-1");
        assertThat(payload.path("active").booleanValue()).isTrue();
        // Products without UoM/substitution data keep the new fields null so consumers can
        // distinguish "no data" from an empty set.
        assertThat(payload.path("trackingLevel").stringValue(null)).isEqualTo("LOT");
        assertThat(payload.path("uomConversions").isNull()).isTrue();
        assertThat(payload.path("substitutionGroupId").isNull()).isTrue();
        assertThat(payload.path("substitutionProductIds").isNull()).isTrue();
    }

    private DomainEventEnvelope<?> capturedEnvelope() {
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DomainEventEnvelope> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("catalog.events.v1"), any());
        verify(outboxEventWriter).publish(any(), captor.capture());
        DomainEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.payload()).isInstanceOf(ProductUpdatedV1.class);
        return envelope;
    }

    private ProductEntity product() {
        ProductEntity product = new ProductEntity();
        product.setId(UUID.fromString("00000000-0000-0000-0000-000000000aaa"));
        product.setSku("SKU-1");
        product.setName("Heavy Duty Wrench");
        product.setUnitOfMeasure("EA");
        product.setStatus(ProductStatus.ACTIVE);
        product.setTrackingLevel(ProductTrackingLevel.LOT);
        product.setCreatedAt(NOW.minusSeconds(3600));
        product.setUpdatedAt(NOW);
        Category category = new Category();
        product.setCategory(category);
        return product;
    }

    private SubstitutionGroupMemberEntity member(SubstitutionGroupEntity group, ProductEntity product) {
        SubstitutionGroupMemberEntity member = new SubstitutionGroupMemberEntity();
        member.setGroup(group);
        member.setProduct(product);
        return member;
    }
}
