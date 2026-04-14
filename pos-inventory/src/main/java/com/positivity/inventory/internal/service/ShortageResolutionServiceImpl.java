package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.ExternalAvailabilityClient;
import com.positivity.inventory.internal.client.ProductSubstituteClient;
import com.positivity.inventory.internal.dto.shortage.ResolutionOption;
import com.positivity.inventory.internal.dto.shortage.ResolutionOptionType;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionResponse;
import com.positivity.inventory.service.ShortageResolutionService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ShortageResolutionServiceImpl implements ShortageResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ShortageResolutionServiceImpl.class);

    private static final long PRODUCT_TIMEOUT_MS = 800L;
    private static final long EXTERNAL_TIMEOUT_MS = 1200L;

    private final ProductSubstituteClient productSubstituteClient;
    private final ExternalAvailabilityClient externalAvailabilityClient;
    private final Executor shortageResolutionExecutor;

    public ShortageResolutionServiceImpl(
            @NonNull ProductSubstituteClient productSubstituteClient,
            @NonNull ExternalAvailabilityClient externalAvailabilityClient,
            @Qualifier("shortageResolutionExecutor") @NonNull Executor shortageResolutionExecutor) {
        this.productSubstituteClient = productSubstituteClient;
        this.externalAvailabilityClient = externalAvailabilityClient;
        this.shortageResolutionExecutor = shortageResolutionExecutor;
    }

    @Override
    public @NonNull ShortageResolutionResponse resolveShortage(@NonNull ShortageResolutionRequest request) {
        String sku = request.getSku();
        int shortQty = request.getShortQuantity() != null ? request.getShortQuantity() : 1;
        boolean partialResultsBanner = false;

        CompletableFuture<List<ResolutionOption>> substituteFuture = CompletableFuture.supplyAsync(
                () -> productSubstituteClient.resolveSubstitutes(sku, shortQty), shortageResolutionExecutor);
        CompletableFuture<List<ResolutionOption>> externalFuture = CompletableFuture.supplyAsync(
                () -> externalAvailabilityClient.resolveExternalOptions(sku, shortQty), shortageResolutionExecutor);

        List<ResolutionOption> substituteOptions = new ArrayList<>();
        try {
            substituteOptions = substituteFuture.get(PRODUCT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            substituteFuture.cancel(true);
            externalFuture.cancel(true);
            log.warn("Product substitute client interrupted for sku(mask)={}: {}", maskForLog(sku), e.getMessage());
            partialResultsBanner = true;
        } catch (TimeoutException e) {
            substituteFuture.cancel(true);
            externalFuture.cancel(true);
            log.warn("Product substitute client timed out for sku(mask)={}: {}", maskForLog(sku), e.getMessage());
            partialResultsBanner = true;
        } catch (ExecutionException e) {
            substituteFuture.cancel(true);
            log.warn("Product substitute client failed for sku(mask)={}: {}", maskForLog(sku), e.getMessage());
            partialResultsBanner = true;
        }

        List<ResolutionOption> externalOptions = new ArrayList<>();
        try {
            externalOptions = externalFuture.get(EXTERNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            externalFuture.cancel(true);
            substituteFuture.cancel(true);
            log.warn("External availability client interrupted for sku(mask)={}: {}", maskForLog(sku), e.getMessage());
            partialResultsBanner = true;
        } catch (TimeoutException e) {
            substituteFuture.cancel(true);
            externalFuture.cancel(true);
            log.warn("External availability client timed out for sku(mask)={}: {}", maskForLog(sku), e.getMessage());
            partialResultsBanner = true;
        } catch (ExecutionException e) {
            externalFuture.cancel(true);
            log.warn("External availability client failed for sku(mask)={}: {}", maskForLog(sku), e.getMessage());
            partialResultsBanner = true;
        }

        Comparator<ResolutionOption> ranker = Comparator.comparingInt(
                        (ResolutionOption option) -> option.getEstimatedLeadTimeDays() != null
                                ? option.getEstimatedLeadTimeDays()
                                : Integer.MAX_VALUE)
                .thenComparing(Comparator.comparing(
                        ResolutionOption::getUnitCost, Comparator.nullsLast(Comparator.naturalOrder())))
                .thenComparing(Comparator.comparing(
                        ResolutionOption::getQualityTier, Comparator.nullsLast(Comparator.reverseOrder())));

        substituteOptions =
                new ArrayList<>(substituteOptions.stream().sorted(ranker).toList());
        externalOptions =
                new ArrayList<>(externalOptions.stream().sorted(ranker).toList());

        List<ResolutionOption> orderedOptions = new ArrayList<>();
        orderedOptions.addAll(substituteOptions);
        orderedOptions.addAll(externalOptions);
        orderedOptions.add(buildBackorderOption(null));

        return ShortageResolutionResponse.builder()
                .allocationId(request.getAllocationId())
                .sku(sku)
                .options(orderedOptions)
                .partialResultsBanner(partialResultsBanner)
                .build();
    }

    private ResolutionOption buildBackorderOption(Integer leadTimeDays) {
        return ResolutionOption.builder()
                .type(ResolutionOptionType.BACKORDER)
                .estimatedLeadTimeDays(leadTimeDays)
                .source(null)
                .confidence(null)
                .build();
    }

    private static String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        if (text.length() <= 4) {
            return "****";
        }
        return "****" + text.substring(text.length() - 4);
    }
}
