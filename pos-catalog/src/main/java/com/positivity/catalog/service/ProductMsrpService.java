package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.CreateMsrpRequestDto;
import com.positivity.catalog.internal.dto.ProductMsrpDto;
import com.positivity.catalog.internal.dto.UpdateMsrpRequestDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ProductMsrpService {
    ProductMsrpDto createMsrp(@NonNull UUID productId, @NonNull CreateMsrpRequestDto request);

    ProductMsrpDto updateMsrp(@NonNull UUID productId, @NonNull UUID msrpId, @NonNull UpdateMsrpRequestDto request);

    Optional<ProductMsrpDto> getActiveMsrp(@NonNull UUID productId, @NonNull LocalDate asOf);

    List<ProductMsrpDto> getAllMsrp(@NonNull UUID productId);
}
