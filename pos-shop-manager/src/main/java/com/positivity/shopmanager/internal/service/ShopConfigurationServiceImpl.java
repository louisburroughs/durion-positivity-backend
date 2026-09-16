package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.ShopResponse;
import com.positivity.shopmanager.internal.dto.ShopUpsertRequest;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of the {@code shop} table.
 *
 * <p>Before this existed nothing in {@code src/main} wrote a shop row — no seed, no bulk-loader
 * domain, no endpoint, no consumer — while every location-parameterised read in this module gates
 * on one: {@code getScheduleView} answers 404 for a day with no appointment when {@code
 * shopRepository.existsById} is false, and the dashboard and roster queries resolve a location the
 * same way. A location could therefore never become schedulable.
 *
 * <p>Deliberately not a replica. {@code ExtLocationReplica} mirrors pos-location and is written
 * only by its Kafka consumer; the shop row is this module's own configuration, and the timezone it
 * carries is a scheduling decision rather than a copy of a location attribute. Hydrating it from
 * {@code location.events.v1} would make every location schedulable the moment it is created,
 * including warehouses and corporate sites that have no bays.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopConfigurationServiceImpl implements ShopConfigurationService {

    private final ShopRepository shopRepository;

    @Override
    @Transactional
    public @NonNull ShopResponse upsert(@NonNull UUID locationId, @NonNull ShopUpsertRequest request) {
        String timezone = normalizeTimezone(request.getTimezone());

        Shop shop = shopRepository
                .findById(locationId)
                .orElseGet(() -> Shop.builder().id(locationId).build());
        shop.setName(request.getName());
        shop.setAddress(request.getAddress());
        shop.setTimezone(timezone);

        Shop saved = shopRepository.save(shop);
        log.info("Upserted shop configuration for location {} (timezone={})", locationId, timezone);
        // Mapped here, not in the controller: controllers work with DTOs, never entities.
        return ShopResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .address(saved.getAddress())
                .timezone(saved.getTimezone())
                .build();
    }

    /**
     * A blank timezone is left unset — {@code resolveZoneId} already falls back to UTC, which is
     * the documented behaviour for a shop with none configured. A non-blank one must be a real
     * zone: the day window is computed in it, so a typo would silently shift every appointment on
     * the board rather than fail anything.
     */
    private String normalizeTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        String trimmed = timezone.trim();
        try {
            ZoneId.of(trimmed);
        } catch (DateTimeException e) {
            throw new ShopManagerValidationException("timezone is not a valid IANA zone id: " + trimmed);
        }
        return trimmed;
    }
}
