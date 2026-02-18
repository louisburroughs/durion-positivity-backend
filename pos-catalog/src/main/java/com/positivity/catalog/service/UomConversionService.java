package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.UomConversionCreateRequestDto;
import com.positivity.catalog.internal.dto.UomConversionDto;
import com.positivity.catalog.internal.dto.UomConversionUpdateRequestDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UomConversionService {

    UomConversionDto createConversion(@NonNull UomConversionCreateRequestDto request);

    UomConversionDto getConversion(@NonNull UUID id);

    List<UomConversionDto> listActiveConversions();

    UomConversionDto updateConversion(@NonNull UUID id, @NonNull UomConversionUpdateRequestDto request);

    void deactivateConversion(@NonNull UUID id);

    BigDecimal getInverseConversionFactor(@NonNull String fromUomCode, @NonNull String toUomCode);
}
