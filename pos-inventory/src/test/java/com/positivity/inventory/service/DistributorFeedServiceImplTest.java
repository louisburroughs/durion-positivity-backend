package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.DistributorFeedItemDto;
import com.positivity.inventory.internal.entity.DistributorFeedException;
import com.positivity.inventory.internal.entity.DistributorNormalizedInventory;
import com.positivity.inventory.internal.enums.DistributorExceptionReason;
import com.positivity.inventory.internal.repository.DistributorFeedExceptionRepository;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.internal.service.DistributorFeedServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DistributorFeedServiceImplTest {

    @Mock
    private DistributorNormalizedInventoryRepository normalizedRepository;

    @Mock
    private DistributorFeedExceptionRepository exceptionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Captor
    private ArgumentCaptor<DistributorNormalizedInventory> normalizedCaptor;

    @Captor
    private ArgumentCaptor<DistributorFeedException> exceptionCaptor;

    private DistributorFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DistributorFeedServiceImpl(normalizedRepository, exceptionRepository, objectMapper);
    }

    @Test
    void processFeed_normalizesHoursRangeAndIsoRegion() {
        DistributorFeedItemDto item = baseItemBuilder()
                .rawLeadTime("24-48 hours")
                .rawShipFromRegion("us-tx")
                .build();

        when(normalizedRepository.findByDistributorIdAndDistributorSku("D1", "SKU-1"))
                .thenReturn(Optional.empty());

        service.processFeed(List.of(item));

        verify(normalizedRepository).save(normalizedCaptor.capture());
        verify(exceptionRepository, never()).save(org.mockito.ArgumentMatchers.any());

        DistributorNormalizedInventory saved = normalizedCaptor.getValue();
        assertThat(saved.getLeadTimeDaysMin()).isEqualTo(1);
        assertThat(saved.getLeadTimeDaysMax()).isEqualTo(2);
        assertThat(saved.getShipFromRegionCode()).isEqualTo("US-TX");
        assertThat(saved.getNormalizationPolicyVersion()).isEqualTo("v1");
    }

    @Test
    void processFeed_acceptsQualitativeLeadTimeBackorder() {
        DistributorFeedItemDto item = baseItemBuilder()
                .rawLeadTime("backorder")
                .rawShipFromRegion("US")
                .build();

        when(normalizedRepository.findByDistributorIdAndDistributorSku("D1", "SKU-1"))
                .thenReturn(Optional.empty());

        service.processFeed(List.of(item));

        verify(normalizedRepository).save(normalizedCaptor.capture());
        DistributorNormalizedInventory saved = normalizedCaptor.getValue();
        assertThat(saved.getLeadTimeDaysMin()).isNull();
        assertThat(saved.getLeadTimeDaysMax()).isNull();
        assertThat(saved.getShipFromRegionCode()).isEqualTo("US");
    }

    @Test
    void processFeed_routesInvalidLeadTimeToExceptionQueue() throws Exception {
        DistributorFeedItemDto item = baseItemBuilder()
                .rawLeadTime("maybe later")
                .rawShipFromRegion("US")
                .build();

        when(objectMapper.writeValueAsString(item)).thenReturn("{\"rawLeadTime\":\"maybe later\"}");

        service.processFeed(List.of(item));

        verify(exceptionRepository).save(exceptionCaptor.capture());
        verify(normalizedRepository, never()).save(org.mockito.ArgumentMatchers.any());

        DistributorFeedException exception = exceptionCaptor.getValue();
        assertThat(exception.getReason()).isEqualTo(DistributorExceptionReason.LEAD_TIME_UNPARSABLE);
        assertThat(exception.getRawPayload()).isEqualTo("{\"rawLeadTime\":\"maybe later\"}");
    }

    @Test
    void processFeed_routesInvalidRegionToExceptionQueue() throws Exception {
        DistributorFeedItemDto item = baseItemBuilder()
                .rawLeadTime("2-3 days")
                .rawShipFromRegion("ABCDEFGHIJ")
                .build();

        when(objectMapper.writeValueAsString(item)).thenReturn("{\"rawShipFromRegion\":\"ABCDEFGHIJ\"}");

        service.processFeed(List.of(item));

        verify(exceptionRepository).save(exceptionCaptor.capture());
        verify(normalizedRepository, never()).save(org.mockito.ArgumentMatchers.any());

        DistributorFeedException exception = exceptionCaptor.getValue();
        assertThat(exception.getReason()).isEqualTo(DistributorExceptionReason.REGION_UNMAPPED);
        assertThat(exception.getRawPayload()).isEqualTo("{\"rawShipFromRegion\":\"ABCDEFGHIJ\"}");
    }

    @Test
    void processFeed_usesFallbackPayloadWhenSerializationFails() throws Exception {
        DistributorFeedItemDto item = baseItemBuilder().productId(null).build();

        when(objectMapper.writeValueAsString(item)).thenThrow(new RuntimeException("serialization failed"));

        service.processFeed(List.of(item));

        verify(exceptionRepository).save(exceptionCaptor.capture());
        DistributorFeedException exception = exceptionCaptor.getValue();

        assertThat(exception.getReason()).isEqualTo(DistributorExceptionReason.SKU_UNMAPPED);
        assertThat(exception.getRawPayload()).contains("DistributorFeedItemDto{");
        assertThat(exception.getRawPayload()).contains("distributorId=D1");
        assertThat(exception.getRawPayload()).contains("distributorSku=SKU-1");
    }

    private DistributorFeedItemDto.DistributorFeedItemDtoBuilder baseItemBuilder() {
        return DistributorFeedItemDto.builder()
                .productId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .distributorId("D1")
                .distributorSku("SKU-1")
                .quantityAvailable(10)
                .rawLeadTime("1-3")
                .rawShipFromRegion("US");
    }
}
