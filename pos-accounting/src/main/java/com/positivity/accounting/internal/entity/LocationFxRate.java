package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Configured FX rates for a location and fiscal year (issue #731, CAP-316 enrichment).
 *
 * <p>{@code localCurrencyPerUsd} is the period-end local-currency-per-USD rate;
 * {@code averageRate} is the average conversion rate used for the year's P&amp;L flows (both
 * {@code 1.00} for US plants — locations without a configured row fall back to that default).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "accounting_location_fx_rate",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_accounting_location_fx_rate",
                        columnNames = {"location_code", "fiscal_year"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LocationFxRate {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "fx_rate_id", columnDefinition = "UUID")
    private UUID fxRateId;

    /** Accounting {@code locationId} dimension value this rate applies to. */
    @NonNull
    @Column(name = "location_code", length = 100, nullable = false)
    private String locationCode;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    /** Local currency units per USD (e.g. {@code 17.150000} for MXN). */
    @NonNull
    @Column(name = "local_currency_per_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal localCurrencyPerUsd;

    /** Average conversion rate used for the year's flows. */
    @NonNull
    @Column(name = "average_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal averageRate;

    @Version
    @Column(name = "version")
    private Integer version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;
}
