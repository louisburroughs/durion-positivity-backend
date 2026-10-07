package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The tenant's accounting-calendar zone (#2558; Accounting Domain ruling 2026-10-06): the one zone in which an
 * instant becomes a posting date and a period. A period close cuts the whole tenant at one instant, so every posting
 * flow and the period service read their dates through here, never through {@code clock.getZone()} or the JVM's
 * default zone (the {@code Clock} bean stays UTC).
 *
 * <p>The zone is the {@code ACCOUNTING_TIME_ZONE} row of {@code accounting_configuration}. There is no default: a
 * tenant without the row fails closed with {@link AccountingTimeZoneUnsetException}, which a consumed fact turns into
 * {@code SUSPENDED / ACCOUNTING_TIME_ZONE_UNSET}. Migration V10 seeded {@code UTC} for the tenants that existed, and
 * tenant provisioning seeds {@code UTC} for every new one, so the row is missing only when someone removed it.
 *
 * <p>Nothing is cached: every call is one read of the tenant's row by its unique {@code (tenant_id, config_key)}
 * index. A per-instance cache would let another instance keep cutting at the old zone after a change, giving one
 * tenant two period boundaries (the review of #2561), and a change must be visible on every instance at once.
 *
 * <p>All reads use the bound tenant's connection (row-level security), so {@link #zoneFor} refuses a tenant other
 * than the bound one.
 */
@Slf4j
@Component
public class AccountingCalendarZoneResolver {

    /** The {@code accounting_configuration} key of the zone. */
    public static final String CONFIG_KEY = "ACCOUNTING_TIME_ZONE";

    private final AccountingConfigurationRepository configurationRepository;
    private final TenantResolver tenantResolver;
    private final Clock clock;

    public AccountingCalendarZoneResolver(
            AccountingConfigurationRepository configurationRepository, TenantResolver tenantResolver, Clock clock) {
        this.configurationRepository = configurationRepository;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
    }

    /**
     * The accounting-calendar zone of {@code tenantId}, which must be the bound tenant.
     *
     * @throws AccountingTimeZoneUnsetException when the tenant has no {@code ACCOUNTING_TIME_ZONE} row
     */
    public @NonNull ZoneId zoneFor(@NonNull UUID tenantId) {
        UUID bound = tenantResolver.require();
        if (!bound.equals(tenantId)) {
            throw new IllegalStateException(
                    "Accounting time zone of tenant " + tenantId + " asked for while tenant " + bound + " is bound");
        }
        return read().orElseThrow(AccountingTimeZoneUnsetException::new);
    }

    /** The bound tenant's accounting-calendar zone. */
    public @NonNull ZoneId zone() {
        return zoneFor(tenantResolver.require());
    }

    /** The bound tenant's zone, or empty when it is not set. */
    public @NonNull Optional<ZoneId> find() {
        return read();
    }

    /** The posting date of {@code instant} in the bound tenant's accounting calendar. */
    public @NonNull LocalDate postingDate(@NonNull Instant instant) {
        return LocalDate.ofInstant(instant, zone());
    }

    /** {@link #postingDate(Instant)} for {@code tenantId}, which must be the bound tenant. */
    public @NonNull LocalDate postingDate(@NonNull Instant instant, @NonNull UUID tenantId) {
        return LocalDate.ofInstant(instant, zoneFor(tenantId));
    }

    /** The posting date-time of {@code instant}: its wall-clock time in the bound tenant's accounting calendar. */
    public @NonNull LocalDateTime postingDateTime(@NonNull Instant instant) {
        return LocalDateTime.ofInstant(instant, zone());
    }

    /**
     * The date-time an ingestion row records for a fact that is held, not posted: the posting date-time when the zone
     * is set, else the instant in UTC, stated explicitly. A held row posts nothing and is re-dated when it is
     * reprocessed, so this is the one place a missing zone does not fail; it is never used for a journal entry.
     */
    public @NonNull LocalDateTime heldRecordDateTime(@NonNull Instant instant) {
        Optional<ZoneId> zone = read();
        return LocalDateTime.ofInstant(instant, zone.orElse(ZoneOffset.UTC));
    }

    /** Today in the bound tenant's accounting calendar. */
    public @NonNull LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    /** The current month in the bound tenant's accounting calendar. */
    public @NonNull YearMonth currentMonth() {
        return YearMonth.now(clock.withZone(zone()));
    }

    private Optional<ZoneId> read() {
        return configurationRepository
                .findByConfigKey(CONFIG_KEY)
                .map(AccountingConfiguration::getConfigValue)
                .flatMap(AccountingCalendarZoneResolver::parse);
    }

    private static Optional<ZoneId> parse(String stored) {
        try {
            return Optional.of(ZoneId.of(stored.trim()));
        } catch (DateTimeException e) {
            // Only the validated PUT and the seeds write the row; an unreadable value is treated as unset, never
            // as a zone guessed from it.
            log.error("Stored ACCOUNTING_TIME_ZONE '{}' is not a zone id; treated as unset", stored);
            return Optional.empty();
        }
    }
}
