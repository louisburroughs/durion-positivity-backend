package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.repository.CatalogRepository;
import com.positivity.catalog.internal.repository.NonInventoryProductRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private NonInventoryProductRepository nonInventoryProductRepository;

    @Mock
    private CatalogRepository catalogRepository;

    @InjectMocks
    private CatalogServiceImpl catalogService;

    private static ServiceEntity service(String name) {
        ServiceEntity e = new ServiceEntity();
        e.setId(UUID.randomUUID());
        e.setName(name);
        e.setShortDescription("short");
        e.setLongDescription("long");
        return e;
    }

    @Test
    void searchServices_blankQuery_returnsEmptyAndSkipsRepository() {
        assertThat(catalogService.searchServices("   ", 20)).isEmpty();
        assertThat(catalogService.searchServices(null, 20)).isEmpty();
        verifyNoInteractions(serviceRepository);
    }

    @Test
    void searchServices_mapsMatchesToDtos() {
        when(serviceRepository.findByNameContainingIgnoreCaseOrderByNameAsc("brake"))
                .thenReturn(List.of(service("Brake pad replacement"), service("Brake fluid flush")));

        List<ServiceDto> result = catalogService.searchServices("brake", 20);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ServiceDto::getName).containsExactly("Brake pad replacement", "Brake fluid flush");
    }

    @Test
    void searchServices_trimsQuery() {
        when(serviceRepository.findByNameContainingIgnoreCaseOrderByNameAsc("oil")).thenReturn(List.of(service("Oil change")));

        assertThat(catalogService.searchServices("  oil  ", 20)).hasSize(1);
    }

    @Test
    void searchServices_capsResultsToLimit() {
        List<ServiceEntity> many =
                IntStream.range(0, 50).mapToObj(i -> service("Service " + i)).toList();
        when(serviceRepository.findByNameContainingIgnoreCaseOrderByNameAsc("service")).thenReturn(many);

        assertThat(catalogService.searchServices("service", 5)).hasSize(5);
    }
}
